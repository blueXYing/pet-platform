package com.petplatform.order.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.command.OrderAutoConfirmApi;
import com.petplatform.order.api.command.OrderAutoConfirmRepairApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderAutoConfirmStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper.Row;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Both confirmation rounds and recovery share one authoritative transaction path. */
public final class OrderAutoConfirmService implements OrderAutoConfirmApi, OrderAutoConfirmRepairApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final PaymentSuccessFactsApi payments;
    private final ReservationConfirmApi reservations;
    private final RefundOrderFactsApi refunds;
    private final IntegrationEventPublisher outbox;
    private final OrderPaymentStore paymentStore;
    private final OrderAutoConfirmStore store;
    private final TaskSubmissionInspector tasks;
    private final JdbcAsyncTaskSubmitter submitter;
    private final TransactionTemplate tx;

    public OrderAutoConfirmService(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, PaymentSuccessFactsApi payments,
            ReservationConfirmApi reservations, RefundOrderFactsApi refunds,
            IntegrationEventPublisher outbox) {
        this.source = Objects.requireNonNull(source); this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard); this.payments = Objects.requireNonNull(payments);
        this.reservations = Objects.requireNonNull(reservations); this.refunds = Objects.requireNonNull(refunds);
        this.outbox = Objects.requireNonNull(outbox);
        paymentStore = new OrderPaymentStore(source); store = new OrderAutoConfirmStore(source);
        tasks = new TaskSubmissionInspector(source); submitter = new JdbcAsyncTaskSubmitter(source, ids);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(15);
    }

    @Override public OrderAutoConfirmApi.Result autoConfirm(AutoConfirmOrderCommand command) {
        if (command == null) throw invalid();
        validate(command.context(), command.orderId(), "TASK:", command.expectedConfirmRound());
        if (command.expectedConfirmRound() < 0 || command.expectedConfirmRound() > 1) throw invalid();
        try { PublicContractChecks.requireMillisecondPrecision(command.expectedConfirmDeadline()); }
        catch (IllegalArgumentException bad) { throw invalid(); }
        return execute(command.context(), command.orderId(), () -> {
            Row row = locked(command.orderId(), command.context());
            return confirm(row, command);
        });
    }

    @Override public OrderAutoConfirmRepairApi.Result repairMissingTask(CommandContext context, String orderId) {
        validate(context, orderId, "REPAIR:", requestRound(context,orderId,"REPAIR:"));
        return execute(context, orderId, () -> repair(locked(orderId, context), context, orderId));
    }

    private OrderAutoConfirmRepairApi.Result repair(Row row, CommandContext context, String orderId) {
        if (stale(row) || row.rescheduleCount != requestRound(context,orderId,"REPAIR:")) return OrderAutoConfirmRepairApi.Result.STALE;
        OffsetDateTime deadline = deadline(row);
        var command = command(context.traceId(), orderId, row.rescheduleCount, deadline);
        OrderAutoConfirmApi.Result eligibility = eligible(row, command);
        if (eligibility == OrderAutoConfirmApi.Result.BLOCKED_BY_REFUND)
            return OrderAutoConfirmRepairApi.Result.BLOCKED_BY_REFUND;
        if (eligibility == OrderAutoConfirmApi.Result.STALE)
            return OrderAutoConfirmRepairApi.Result.STALE;
        TaskSubmissionSnapshot existing = tasks.find(OrderAutoConfirmTaskSpec.key(orderId,row.rescheduleCount));
        if (existing == null) {
            submitter.enqueueAt(OrderAutoConfirmTaskSpec.key(orderId,row.rescheduleCount), "ORDER", OrderAutoConfirmTaskSpec.TYPE,
                    "ORDER", row.id, null, OrderAutoConfirmTaskSpec.payload(orderId,row.rescheduleCount, deadline),
                    OrderAutoConfirmTaskSpec.MAX_RETRIES, OrderAutoConfirmTaskSpec.TYPE, deadline);
            return OrderAutoConfirmRepairApi.Result.CREATED;
        }
        if (!OrderAutoConfirmTaskSpec.matches(existing, orderId,row.rescheduleCount, deadline))
            throw conflict("TASK_BINDING_CONFLICT");
        if (Set.of("READY", "RETRY_WAIT", "RUNNING").contains(existing.status()))
            return OrderAutoConfirmRepairApi.Result.EXISTS;
        if (!Set.of("DEAD", "CANCELED", "SUCCEEDED").contains(existing.status()))
            throw unavailable("TASK_STATUS_UNKNOWN");
        anomaly(row, context.requestId(), "TERMINAL_TASK_" + existing.status());
        if (paymentStore.databaseNow().isBefore(deadline)) return OrderAutoConfirmRepairApi.Result.NOT_DUE;
        complete(row, command);
        return OrderAutoConfirmRepairApi.Result.RECOVERED;
    }

    private OrderAutoConfirmApi.Result confirm(Row row, AutoConfirmOrderCommand command) {
        if (row == null) return OrderAutoConfirmApi.Result.STALE;
        if (row.rescheduleCount == null || row.rescheduleCount < 0 || row.rescheduleCount > 1)
            throw unavailable("CONFIRM_ROUND_INVALID");
        if (row.rescheduleCount != command.expectedConfirmRound()) return OrderAutoConfirmApi.Result.STALE;
        if(!stale(row)||!store.proofs(row.id,command.context().requestId()).isEmpty())OrderConfirmEpoch.requireSchedule(source,row,reservations,"CANCELED".equals(row.orderStage),query(command.context()));
        var proofs = store.proofs(row.id, command.context().requestId());
        if (!proofs.isEmpty()) {
            if (proofs.size() != 1) throw unavailable("CONFIRMATION_PROOF_INVALID");
            try {
                var proof = JSON.readTree(proofs.getFirst());
                if (proof == null || !proof.isObject() || proof.size() != 8
                        || !proof.path("confirmRound").isIntegralNumber()
                        || !proof.path("confirmRound").canConvertToInt())
                    throw unavailable("CONFIRMATION_PROOF_INVALID");
                for (String field : List.of("orderId","reservationId","storeId","eventId",
                        "confirmMode","confirmDeadline","confirmedAt"))
                    if (!proof.path(field).isTextual()) throw unavailable("CONFIRMATION_PROOF_INVALID");
                if (!command.expectedConfirmDeadline().isEqual(OffsetDateTime.parse(proof.path("confirmDeadline").asText())))
                    throw conflict("CONFIRM_DEADLINE_CONFLICT");
                if (!command.orderId().equals(proof.path("orderId").asText())
                        || proof.path("confirmRound").asInt(-1) != command.expectedConfirmRound()
                        || !"AUTO".equals(proof.path("confirmMode").asText())
                        || !IDS.toApi(row.reservationId).equals(proof.path("reservationId").asText())
                        || !IDS.toApi(row.storeId).equals(proof.path("storeId").asText())
                        || !"AUTO".equals(row.confirmMode) || row.confirmedAt == null
                        || !deadline(row).isEqual(command.expectedConfirmDeadline())
                        || !row.confirmedAt.atOffset(ZoneOffset.UTC).isEqual(
                                OffsetDateTime.parse(proof.path("confirmedAt").asText()))
                        || !Set.of("PENDING_SERVICE","COMPLETED","CANCELED")
                                .contains(row.orderStage == null ? "" : row.orderStage))
                    throw unavailable("CONFIRMATION_PROOF_INVALID");
                IDS.fromApi(proof.path("eventId").asText());
                return OrderAutoConfirmApi.Result.ALREADY_CONFIRMED;
            } catch (ApiException known) { throw known; }
            catch (Exception broken) { throw unavailable("CONFIRMATION_PROOF_INVALID"); }
        }
        if ("AUTO".equals(row.confirmMode)) throw unavailable("CONFIRMATION_PROOF_MISSING");
        var eligibility = eligible(row, command);
        if (eligibility != null) return eligibility;
        if (paymentStore.databaseNow().isBefore(command.expectedConfirmDeadline()))
            return OrderAutoConfirmApi.Result.NOT_DUE;
        complete(row, command);
        return OrderAutoConfirmApi.Result.CONFIRMED;
    }

    /** Null means eligible; no due-time check here so repair can submit future tasks. */
    private OrderAutoConfirmApi.Result eligible(Row row, AutoConfirmOrderCommand command) {
        if (stale(row) || row.rescheduleCount != command.expectedConfirmRound()) return OrderAutoConfirmApi.Result.STALE;
        OrderConfirmEpoch.requireSchedule(source,row,reservations,"CANCELED".equals(row.orderStage),query(command.context()));
        if (!store.proofs(row.id, command.context().requestId()).isEmpty())
            throw unavailable("CONFIRMATION_PROOF_INVALID");
        OffsetDateTime deadline = deadline(row);
        if (!deadline.isEqual(command.expectedConfirmDeadline())) throw conflict("CONFIRM_DEADLINE_CONFLICT");
        if (!"PAID".equals(row.paymentStatus) || !"UNVERIFIED".equals(row.verificationStatus)
                || row.canceledAt != null || row.cancelReason != null || row.confirmMode != null
                || row.confirmedAt != null || row.refundedAmount == null || row.refundedAmount.signum() != 0
                || row.payAmount == null || row.payAmount.signum() <= 0)
            throw unavailable("ORDER_FACTS_INVALID");
        var previous = paymentStore.lockResult(row.id);
        if (previous == null || !"NORMAL".equals(previous.resultType())
                || previous.paymentId() <= 0 || previous.sourceEventId() <= 0)
            throw unavailable("NORMAL_PAYMENT_PROOF_MISSING");
        String orderId = command.orderId(), storeId = IDS.toApi(row.storeId);
        QueryContext query = query(command.context());
        var paid = payments.requireSucceeded(IDS.toApi(previous.paymentId()), orderId, storeId, query);
        if (paid == null || !orderId.equals(paid.orderId()) || !storeId.equals(paid.storeId())
                || !IDS.toApi(row.merchantId).equals(paid.merchantId())
                || !IDS.toApi(row.userId).equals(paid.userId())
                || !IDS.toApi(previous.paymentId()).equals(paid.paymentId())
                || !IDS.toApi(previous.sourceEventId()).equals(paid.successEventId())
                || previous.channelTradeNo() == null || !previous.channelTradeNo().equals(paid.channelTradeNo())
                || paid.paidAmount() == null || previous.paidAmount() == null
                || row.payAmount.compareTo(paid.paidAmount()) != 0
                || previous.paidAmount().compareTo(paid.paidAmount()) != 0
                || paid.paidAt() == null || previous.paidAt() == null
                || !previous.paidAt().isEqual(paid.paidAt()) || row.paidAt == null
                || !row.paidAt.atOffset(ZoneOffset.UTC).isEqual(paid.paidAt())
                || !"CNY".equals(paid.currency()))
            throw unavailable("PAYMENT_FACTS_MISMATCH");
        try {
            IDS.fromApi(paid.paymentNo()); PublicContractChecks.requireMillisecondPrecision(paid.paidAt());
        } catch (RuntimeException bad) { throw unavailable("PAYMENT_FACTS_MISMATCH"); }
        reservations.assertConfirmed(orderId, IDS.toApi(row.reservationId), storeId, query);
        var fact = refunds.findByOrder(orderId, storeId, query);
        if (fact == null) throw unavailable("REFUND_FACTS_UNAVAILABLE");
        for (var refund : fact.refunds()) {
            if (!orderId.equals(refund.orderId()) || refund.status() == null || refund.status().isBlank())
                throw unavailable("REFUND_FACTS_INVALID");
            IDS.fromApi(refund.refundOrderId());
        }
        if (row.refundOrderId != null || fact.exists()) {
            if (row.refundOrderId == null || fact.refunds().stream().noneMatch(
                    r -> IDS.toApi(row.refundOrderId).equals(r.refundOrderId())))
                anomaly(row, command.context().requestId(), "REFUND_BINDING_MISMATCH");
            return OrderAutoConfirmApi.Result.BLOCKED_BY_REFUND;
        }
        if (row.currentRefundApplicationId != null || row.currentAftersaleId != null)
            throw unavailable("APPLICATION_FACTS_UNAVAILABLE");
        return null;
    }

    private void complete(Row row, AutoConfirmOrderCommand command) {
        OffsetDateTime now = paymentStore.databaseNow();
        if (now.isBefore(command.expectedConfirmDeadline())) throw unavailable("DATABASE_CLOCK_MOVED_BACK");
        if (store.confirm(row.id, row.version, row.rescheduleCount, row.confirmDeadline, now.toLocalDateTime()) != 1)
            throw unavailable("CONFIRM_CAS_FAILED");
        String eventId = IDS.toApi(ids.nextId());
        var payload = new LinkedHashMap<String,Object>();
        payload.put("orderId", command.orderId()); payload.put("reservationId", IDS.toApi(row.reservationId));
        payload.put("storeId", IDS.toApi(row.storeId)); payload.put("confirmRound", row.rescheduleCount);
        payload.put("confirmMode", "AUTO"); payload.put("confirmDeadline", deadline(row).toString());
        payload.put("confirmedAt", now.toString());
        outbox.publish(new IntegrationEvent<>(eventId, "OrderConfirmedEvent.v1", 1, now, "ORDER",
                command.orderId(), command.context().traceId(), payload));
        var proof = new LinkedHashMap<>(payload); proof.put("eventId", eventId);
        store.log(ids.nextId(), row.id, row.orderStage, "PENDING_SERVICE", "ORDER_AUTO_CONFIRMED",
                command.context().requestId(), json(proof));
    }

    private Row locked(String orderId, CommandContext context) {
        paymentStore.sessionDefaults();
        var located = paymentStore.locate(IDS.fromApi(orderId));
        if (located.isEmpty()) return null;
        if (located.size() != 1 || located.getFirst().storeId() <= 0) throw unavailable("ORDER_BINDING_INVALID");
        String storeId = IDS.toApi(located.getFirst().storeId());
        guard.acquire(List.of(storeId), query(context)); guard.requireHeld(storeId, source);
        Row row = store.lock(IDS.fromApi(orderId));
        if (row == null || row.storeId == null || row.storeId != located.getFirst().storeId()
                || row.userId == null || row.userId <= 0 || row.merchantId == null || row.merchantId <= 0
                || row.reservationId == null || row.reservationId <= 0 || row.version == null)
            throw unavailable("ORDER_BINDING_INVALID");
        return row;
    }

    private <T> T execute(CommandContext context,String orderId,java.util.function.Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("OWN_TRANSACTION_REQUIRED");
        try { return tx.execute(status -> work.get()); }
        catch (RuntimeException failure) {
            String code = failure instanceof ApiException known ? known.getMessage() : "TRANSACTION_UNAVAILABLE";
            // The business transaction is already rolled back. Persist evidence in an independent transaction.
            recordAnomaly(orderId, context.traceId(), code);
            if (failure instanceof ApiException known) throw known;
            throw unavailable("TRANSACTION_UNAVAILABLE");
        }
    }

    public void recordAnomaly(String orderId, String traceId, String code) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable("OWN_TRANSACTION_REQUIRED");
        if (code == null || !code.matches("[A-Z_]{1,64}")) code = "DEPENDENCY_UNAVAILABLE";
        final String safeCode = code;
        try {
            tx.executeWithoutResult(status -> {
                var context = new CommandContext("TASK:" + OrderAutoConfirmTaskSpec.key(orderId),
                        traceId, OperatorType.SYSTEM, null, "TASK");
                Row row = locked(orderId, context);
                if (row != null) anomaly(row, "TASK:"+OrderAutoConfirmTaskSpec.key(orderId,
                        row.rescheduleCount!=null&&row.rescheduleCount>=0&&row.rescheduleCount<=1?row.rescheduleCount:0), safeCode);
            });
        } catch (RuntimeException unavailable) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Auto-confirm diagnostic persistence unavailable for order {0}", orderId);
        }
    }

    private void anomaly(Row row,String requestId,String code) {
        String remark = json(Map.of("code",code,"confirmRound",row.rescheduleCount));
        if (!store.hasAnomaly(row.id,requestId,remark))
            store.log(ids.nextId(),row.id,row.orderStage,row.orderStage,"AUTO_CONFIRM_ANOMALY",requestId,remark);
    }

    /** Bounded owner-table cursor. Each mutation uses its own guarded transaction. */
    public long reconcile(long after,int limit,boolean repair) {
        if (after < 0 || limit < 1 || limit > 100) throw invalid();
        var candidates = store.candidates(after,limit);
        for (Long id : candidates) {
            String orderId = IDS.toApi(id);
            try {
                int round=tx.execute(status -> {
                    Row row=locked(orderId,new CommandContext("SCAN:"+orderId,"AUTO_CONFIRM_SCAN",OperatorType.SYSTEM,null,"SCAN"));
                    if(row==null||row.rescheduleCount==null||row.rescheduleCount<0||row.rescheduleCount>1)throw invalid();
                    return row.rescheduleCount;
                });
                if (repair) repairMissingTask(new CommandContext("REPAIR:" + OrderAutoConfirmTaskSpec.key(orderId,round),
                        "AUTO_CONFIRM_REPAIR",OperatorType.SYSTEM,null,"REPAIR"),orderId);
                else {
                    var task = tasks.find(OrderAutoConfirmTaskSpec.key(orderId,round));
                    if (task != null && Set.of("DEAD","CANCELED","SUCCEEDED").contains(task.status()))
                        recordAnomaly(orderId,"AUTO_CONFIRM_SCAN","TERMINAL_TASK_" + task.status());
                }
            } catch (RuntimeException failure) {
                recordAnomaly(orderId,"AUTO_CONFIRM_SCAN","REPAIR_REQUIRES_REVIEW");
            }
        }
        return candidates.size() < limit ? 0 : candidates.getLast();
    }

    public static AutoConfirmOrderCommand command(String traceId,String orderId,OffsetDateTime deadline) {
        return command(traceId,orderId,0,deadline);
    }
    public static AutoConfirmOrderCommand command(String traceId,String orderId,int round,OffsetDateTime deadline) {
        return new AutoConfirmOrderCommand(new CommandContext("TASK:" + OrderAutoConfirmTaskSpec.key(orderId,round),
                traceId,OperatorType.SYSTEM,null,"TASK"),orderId,round,deadline);
    }
    private static boolean stale(Row row) {
        if (row == null) return true;
        if (row.rescheduleCount == null || row.rescheduleCount < 0 || row.rescheduleCount > 1)
            throw unavailable("CONFIRM_ROUND_INVALID");
        if (!Set.of("PENDING_PAYMENT","PENDING_CONFIRM","PENDING_SERVICE","COMPLETED","CANCELED")
                .contains(row.orderStage == null ? "" : row.orderStage))
            throw unavailable("ORDER_STAGE_INVALID");
        return !"PENDING_CONFIRM".equals(row.orderStage);
    }
    private static OffsetDateTime deadline(Row row) {
        if (row.confirmDeadline == null) throw unavailable("CONFIRM_DEADLINE_MISSING");
        return row.confirmDeadline.atOffset(ZoneOffset.UTC);
    }
    private static QueryContext query(CommandContext c) { return new QueryContext(c.traceId(),OperatorType.SYSTEM,c.operatorId()); }
    private static void validate(CommandContext c,String orderId,String prefix,int round) {
        try {
            IDS.fromApi(orderId); PublicContractChecks.requireCommandRequestId(c);
            if (c.traceId() == null || c.traceId().isBlank() || c.source() == null || c.source().isBlank()) throw invalid();
            if (c.operatorType() != OperatorType.SYSTEM) throw new ApiException(CommonApiCodes.FORBIDDEN,"SYSTEM only");
            if (!(prefix + OrderAutoConfirmTaskSpec.key(orderId,round)).equals(c.requestId())) throw invalid();
        } catch (IllegalArgumentException bad) { throw invalid(); }
    }
    private static int requestRound(CommandContext c,String id,String prefix) {
        if(c==null)throw invalid();
        for(int round=0;round<=1;round++)if((prefix+OrderAutoConfirmTaskSpec.key(id,round)).equals(c.requestId()))return round;
        throw invalid();
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception failed) { throw unavailable("SERIALIZATION_FAILED"); }
    }
    private static ApiException invalid() { return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid auto-confirm command"); }
    private static ApiException unavailable(String code) { return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,code); }
    private static ApiException conflict(String code) { return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,code); }
}
