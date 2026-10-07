package com.petplatform.schedule.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Merchant workbench schedule read shapes (SCH-001 handover: the fine-grained states of the
 * merchant-side calendar belong to SCH-004, SCH-D10). Includes CLOSED windows and versions so
 * M-002 can drive optimistic updates; never exposes other merchants' rows.
 */
public final class ScheduleWorkbenchTypes {
    private ScheduleWorkbenchTypes() {}

    public record WindowItem(String windowId, String merchantId, String storeId, String serviceId,
            String windowKind, OffsetDateTime startAt, OffsetDateTime endAt, int configuredCapacity,
            String status, String version, OffsetDateTime updatedAt) {}

    /** Pagination envelope (53号 §3.3): total/page/pageSize are null in the legacy unpaginated
     * mode (response stays the pre-2026-10-07 {storeId,items} shape); in paged mode total is
     * the filter-matched count independent of the requested page. */
    public record WindowPage(String storeId, List<WindowItem> items, Long total, Integer page,
            Integer pageSize) {
        public WindowPage { items = List.copyOf(items); }
    }

    public record StaffWindowItem(String windowId, String merchantId, String storeId,
            String staffId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
            String version, OffsetDateTime updatedAt) {}

    public record StaffWindowPage(String storeId, String staffId, List<StaffWindowItem> items) {
        public StaffWindowPage { items = List.copyOf(items); }
    }
}
