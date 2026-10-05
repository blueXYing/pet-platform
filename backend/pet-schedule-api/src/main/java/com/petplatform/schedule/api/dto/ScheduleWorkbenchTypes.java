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

    public record WindowPage(String storeId, List<WindowItem> items) {
        public WindowPage { items = List.copyOf(items); }
    }

    public record StaffWindowItem(String windowId, String merchantId, String storeId,
            String staffId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
            String version, OffsetDateTime updatedAt) {}

    public record StaffWindowPage(String storeId, String staffId, List<StaffWindowItem> items) {
        public StaffWindowPage { items = List.copyOf(items); }
    }
}
