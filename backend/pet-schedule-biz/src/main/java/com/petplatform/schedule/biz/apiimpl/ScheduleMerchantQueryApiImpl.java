package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.StaffWindowItem;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.StaffWindowPage;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.WindowItem;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.WindowPage;
import com.petplatform.schedule.api.query.ScheduleMerchantQueryApi;
import com.petplatform.schedule.api.query.WorkbenchStaffWindowQuery;
import com.petplatform.schedule.api.query.WorkbenchWindowQuery;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleReadStore;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleReadMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merchant workbench schedule reads (SCH-D10: the merchant calendar's fine-grained states live
 * with SCH-004). Owner-gated read-only projections over one repeatable-read snapshot; CLOSED
 * windows and versions are included so M-002 can drive optimistic updates. No guard is taken:
 * these reads never change protected facts and every write re-validates under the guard.
 */
public final class ScheduleMerchantQueryApiImpl implements ScheduleMerchantQueryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> STATUSES = Set.of("OPEN", "CLOSED", "SOLD_OUT", "AVAILABLE");
    private static final Set<String> KINDS = Set.of("GENERAL", "PICKUP", "RETURN");
    // 53号 §3.3 (2026-10-07): the notification-list pagination baseline (10号 §3.14 先例),
    // re-validated here so a direct API caller gets the same 400 as the HTTP surface.
    private static final int PAGE_MAX = 10_000;
    private static final int PAGE_SIZE_MAX = 50;

    private final ScheduleReadStore store;
    private final ScheduleAdmissionGate admissions;

    public ScheduleMerchantQueryApiImpl(ScheduleReadStore store, ScheduleAdmissionGate admissions) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
    }

    /** Terminal assembly entry: the read store is wired inside the owning module from the
     * shared DataSource so terminals never touch schedule persistence types (ARCH-002). */
    public ScheduleMerchantQueryApiImpl(javax.sql.DataSource source,
            ScheduleAdmissionGate admissions) {
        this(new ScheduleReadStore(source), admissions);
    }

    @Override
    public WindowPage listWindows(WorkbenchWindowQuery q) {
        user(q == null ? null : q.context());
        positive(q.merchantId(), "merchantId");
        positive(q.storeId(), "storeId");
        if (q.windowKind() != null && !KINDS.contains(q.windowKind())) throw invalid("windowKind");
        if (q.status() != null && !STATUSES.contains(q.status())) throw invalid("status");
        boolean paged = q.page() != null || q.pageSize() != null;
        if (paged && (q.page() == null || q.pageSize() == null)) throw invalid("page/pageSize");
        if (q.page() != null && (q.page() < 1 || q.page() > PAGE_MAX)) throw invalid("page");
        if (q.pageSize() != null && (q.pageSize() < 1 || q.pageSize() > PAGE_SIZE_MAX)) {
            throw invalid("pageSize");
        }
        return store.read(mapper -> {
            admissions.requireOperable(q.context(), id(q.merchantId()), id(q.storeId()));
            long storeId = id(q.storeId());
            Long serviceId = q.serviceId() == null ? null : id(q.serviceId());
            // Same repeatable-read snapshot for rows and total, so a paged answer never
            // mixes two moments of the table (53号 §3.3).
            Long total = paged ? mapper.countWindows(storeId, serviceId,
                    q.windowKind(), q.status()) : null;
            List<Map<String, Object>> rows = mapper.listWindows(storeId, serviceId,
                    q.windowKind(), q.status(), paged ? q.pageSize() : null,
                    paged ? (q.page() - 1) * q.pageSize() : 0);
            List<WindowItem> items = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                items.add(new WindowItem(
                        ScheduleSqlRows.id(row, "id"),
                        ScheduleSqlRows.id(row, "merchant_id"),
                        ScheduleSqlRows.id(row, "store_id"),
                        ScheduleSqlRows.id(row, "service_id"),
                        ScheduleSqlRows.text(row, "window_kind"),
                        ScheduleSqlRows.at(row, "start_at"),
                        ScheduleSqlRows.at(row, "end_at"),
                        (int) ScheduleSqlRows.number(row, "configured_capacity"),
                        ScheduleSqlRows.text(row, "status"),
                        ScheduleSqlRows.version(row, "version"),
                        ScheduleSqlRows.at(row, "updated_at")));
            }
            return new WindowPage(q.storeId(), items, total, paged ? q.page() : null,
                    paged ? q.pageSize() : null);
        });
    }

    @Override
    public StaffWindowPage listStaffWindows(WorkbenchStaffWindowQuery q) {
        user(q == null ? null : q.context());
        positive(q.merchantId(), "merchantId");
        positive(q.storeId(), "storeId");
        positive(q.staffId(), "staffId");
        return store.read(mapper -> {
            admissions.requireOperable(q.context(), id(q.merchantId()), id(q.storeId()));
            List<Map<String, Object>> rows =
                    mapper.listStaffWindows(id(q.storeId()), id(q.staffId()));
            List<StaffWindowItem> items = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                items.add(new StaffWindowItem(
                        ScheduleSqlRows.id(row, "id"),
                        q.merchantId(),
                        ScheduleSqlRows.id(row, "store_id"),
                        ScheduleSqlRows.id(row, "staff_id"),
                        ScheduleSqlRows.at(row, "start_at"),
                        ScheduleSqlRows.at(row, "end_at"),
                        ScheduleSqlRows.text(row, "status"),
                        ScheduleSqlRows.version(row, "version"),
                        ScheduleSqlRows.at(row, "updated_at")));
            }
            return new StaffWindowPage(q.storeId(), q.staffId(), items);
        });
    }

    private static void user(QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "merchant session is required");
        }
    }

    private static long id(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id > 0) return id;
        } catch (RuntimeException ignored) { }
        throw invalid("positive public ID is required");
    }

    private static void positive(String value, String field) {
        if (value == null || value.isBlank()) invalid(field);
        id(value);
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
