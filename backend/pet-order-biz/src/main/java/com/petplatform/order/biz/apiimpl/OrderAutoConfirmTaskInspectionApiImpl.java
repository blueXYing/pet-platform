package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi;
import com.petplatform.order.biz.application.OrderAutoConfirmTaskSpec;
import com.petplatform.order.biz.infrastructure.persistence.OrderAutoConfirmInspectionStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmInspectionMapper.Row;
import com.petplatform.task.core.TaskSubmissionInspector;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** B-only diagnostics. No refund-absence inference, business command, backfill or task state mutation. */
public final class OrderAutoConfirmTaskInspectionApiImpl implements OrderAutoConfirmTaskInspectionApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final OrderAutoConfirmInspectionStore orders;
    private final TaskSubmissionInspector tasks;
    private final TransactionTemplate transaction;

    public OrderAutoConfirmTaskInspectionApiImpl(DataSource source) {
        Objects.requireNonNull(source);
        orders = new OrderAutoConfirmInspectionStore(source);
        tasks = new TaskSubmissionInspector(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
    }

    @Override public Page inspect(QueryContext context, String afterOrderId, int limit) {
        if (context == null || context.operatorType() != OperatorType.SYSTEM)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "Task inspection requires SYSTEM");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "Inspection requires its own read-only transaction");
        final long cursor;
        try {
            cursor = afterOrderId == null ? 0L : IDS.fromApi(afterOrderId);
            if (limit < 1 || limit > 100) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "Invalid inspection cursor or limit");
        }
        try {
            return Objects.requireNonNull(transaction.execute(status -> scan(cursor, limit)));
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "Task inspection facts unavailable");
        }
    }

    private Page scan(long cursor, int limit) {
        LocalDateTime now = orders.databaseNow();
        var rows = orders.scan(cursor, limit + 1);
        boolean more = rows.size() > limit;
        var items = new ArrayList<Item>();
        var counts = new EnumMap<Finding, Integer>(Finding.class);
        for (var row : rows.subList(0, Math.min(rows.size(), limit))) {
            String id = IDS.toApi(row.id);
            var deadline = row.confirmDeadline == null ? null : row.confirmDeadline.atOffset(ZoneOffset.UTC);
            String taskStatus = null;
            Finding finding;
            if (row.rescheduleCount == null || row.rescheduleCount != 0) {
                finding = Finding.UNSUPPORTED_ROUND;
            } else if (!hasNormalOrderFacts(row)) {
                finding = Finding.ORDER_FACTS_REQUIRE_REVIEW;
            } else {
                var task = tasks.find(OrderAutoConfirmTaskSpec.key(id));
                if (task == null) finding = Finding.MISSING_TASK;
                else {
                    taskStatus = task.status();
                    if (!OrderAutoConfirmTaskSpec.matches(task, id, deadline)) finding = Finding.TASK_BINDING_CONFLICT;
                    else if (Set.of("READY", "RETRY_WAIT", "RUNNING").contains(task.status())) finding = Finding.ACTIVE_TASK;
                    else finding = Finding.TERMINAL_TASK_REQUIRES_REVIEW;
                }
            }
            items.add(new Item(id, row.rescheduleCount, deadline,
                    row.confirmDeadline != null && !row.confirmDeadline.isAfter(now), taskStatus, finding));
            counts.merge(finding, 1, Integer::sum);
        }
        return new Page(items, more ? items.getLast().orderId() : null, counts);
    }

    private static boolean hasNormalOrderFacts(Row row) {
        return "PAID".equals(row.paymentStatus) && "UNVERIFIED".equals(row.verificationStatus)
                && "NORMAL".equals(row.resultType) && row.paymentId != null && row.paymentId > 0
                && row.sourceEventId != null && row.sourceEventId > 0
                && row.cancelReason == null && row.canceledAt == null && row.refundOrderId == null
                && row.currentRefundApplicationId == null && row.currentAftersaleId == null
                && row.confirmMode == null && row.confirmedAt == null
                && row.refundedAmount != null && row.refundedAmount.signum() == 0
                && row.payAmount != null && row.payAmount.signum() > 0 && row.channelPaidAmount != null
                && row.payAmount.compareTo(row.channelPaidAmount) == 0 && row.paidAt != null
                && row.paidAt.equals(row.channelPaidAt) && row.confirmDeadline != null
                && row.paidAt.plusMinutes(30).equals(row.confirmDeadline);
    }
}
