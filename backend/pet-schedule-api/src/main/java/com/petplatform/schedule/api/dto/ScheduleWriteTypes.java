package com.petplatform.schedule.api.dto;

import com.petplatform.common.CommandContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Schedule merchant write-side shapes (34号 Schedule-Protection-Contract §2~§4, write-proposal
 * v0.2 §2 approved direction). IDs are Snowflake decimal Strings, versions are non-negative
 * decimal Strings, times are minute-precision instants; the store/service/kind identity of a
 * window is fixed at creation and never transferable through an update.
 */
public final class ScheduleWriteTypes {
    private ScheduleWriteTypes() {}

    // ------------------------------------------------------------------ service windows

    public record CreateWindowCommand(CommandContext context, String merchantId, String storeId,
            String serviceId, String windowKind, OffsetDateTime startAt, OffsetDateTime endAt,
            int configuredCapacity) {}

    public record UpdateWindowCommand(CommandContext context, String windowId, String merchantId,
            String storeId, OffsetDateTime startAt, OffsetDateTime endAt,
            Integer configuredCapacity, long expectedVersion, String reason) {}

    public record WindowCloseCommand(CommandContext context, String windowId, String merchantId,
            String storeId, long expectedVersion, String reason) {}

    public record WindowOpenCommand(CommandContext context, String windowId, String merchantId,
            String storeId, long expectedVersion) {}

    /** Calendar-day range in the platform business zone (Asia/Shanghai, 23号 §2). */
    public record BatchCloseCommand(CommandContext context, String merchantId, String storeId,
            LocalDate fromDate, LocalDate toDate, String reason) {}

    public record WindowResult(String windowId, String merchantId, String storeId, String serviceId,
            String windowKind, OffsetDateTime startAt, OffsetDateTime endAt, int configuredCapacity,
            String status, String version) {}

    public record BlockedWindow(WindowResult window, String reasonCode) {}

    public record BatchCloseResult(String storeId, List<WindowResult> closedWindows,
            List<BlockedWindow> blockedWindows) {
        public BatchCloseResult {
            closedWindows = List.copyOf(closedWindows);
            blockedWindows = List.copyOf(blockedWindows);
        }
    }

    // ------------------------------------------------------------------ staff availability

    public record CreateStaffWindowCommand(CommandContext context, String merchantId,
            String storeId, String staffId, OffsetDateTime startAt, OffsetDateTime endAt) {}

    /** A time move/shorten is an availability reduction: {@code reason} is mandatory whenever
     * the new interval does not fully cover the old one (SCHW-D6/PRD29). */
    public record UpdateStaffWindowCommand(CommandContext context, String windowId,
            String merchantId, String storeId, String staffId, OffsetDateTime startAt,
            OffsetDateTime endAt, long expectedVersion, String reason) {}

    public record StaffWindowCloseCommand(CommandContext context, String windowId,
            String merchantId, String storeId, String staffId, long expectedVersion,
            String reason) {}

    public record StaffWindowOpenCommand(CommandContext context, String windowId,
            String merchantId, String storeId, String staffId, long expectedVersion) {}

    public record StaffWindowResult(String windowId, String merchantId, String storeId,
            String staffId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
            String version) {}

    // ------------------------------------------------------------------ staff capabilities

    public record CapabilityQuery(CommandContext context, String merchantId, String storeId,
            String staffId) {}

    public record CapabilityView(String merchantId, String storeId, String staffId,
            List<String> serviceIds, String version) {}

    /** Full-replacement set (SCHC-2): duplicates are rejected, removals require {@code reason}
     * and the approved reduction protection. */
    public record ReplaceCapabilitiesCommand(CommandContext context, String merchantId,
            String storeId, String staffId, List<String> serviceIds, String expectedVersion,
            String reason) {}

    public record CapabilityResult(String merchantId, String storeId, String staffId,
            List<String> serviceIds, String version) {}
}
