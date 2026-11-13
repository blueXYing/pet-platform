package com.petplatform.schedule.api.command;

import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchGenerateCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchGenerateResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowReplaceCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.ReplaceCapabilitiesCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WeekTemplateQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WeekTemplateReplaceCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WeekTemplateResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WeekTemplateView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;

/**
 * Merchant schedule write commands (SCH-004; 34号 §2~§4 and write-proposal v0.2 §2). Every
 * command is requestId-idempotent per 23号, owner-gated, guarded by the per-store
 * ScheduleCapacityGuardApi inside one top-level local transaction, and audited append-only.
 * Distinct from 07号 §6.2 ScheduleCommandApi (reservation lifecycle), which is untouched.
 */
public interface ScheduleMerchantCommandApi {

    /** @return true when executed now, false when an identical replay returned the first
     *     receipt; the adapter maps that flag to 201 vs 200 (23号 §6). */
    boolean createWindowOutcome(CreateWindowCommand command, WindowResult[] out);

    WindowResult updateWindow(UpdateWindowCommand command);

    WindowResult closeWindow(WindowCloseCommand command);

    WindowResult openWindow(WindowOpenCommand command);

    BatchCloseResult batchClose(BatchCloseCommand command);

    /** CCR-SCH-SLOT-002 覆盖重建 batch command (53号 §3.4): closes the range's open-derived
     * slots (occupied ones are skipped and listed), then creates minute-level unified slots
     * stepping intervalMinutes. Partial success is reported honestly; replays return the first
     * receipt (200). */
    BatchGenerateResult batchGenerate(BatchGenerateCommand command);

    /** CCR-SCH-SLOT-002 store bookable range; an absent row answers the defaults 60/7 at
     * version "0" without creating anything. */
    BookingWindowView getBookingWindow(BookingWindowQuery query);

    /** CCR-SCH-SLOT-002 full replacement with expectedVersion optimistic locking; a stale
     *     version is 409 COMMON_CONFLICT, replays return the first receipt. */
    BookingWindowResult replaceBookingWindow(BookingWindowReplaceCommand command);

    /** CCR-SCH-SLOT-003 weekly cycling template read; an absent row answers seven closed
     *     days at logical version "0" without creating anything. */
    WeekTemplateView getWeekTemplate(WeekTemplateQuery query);

    /** CCR-SCH-SLOT-003 full replacement of the whole 7-day cycle with expectedVersion
     *     optimistic locking; a stale version is 409 COMMON_CONFLICT, replays return the
     *     first receipt. Editing never touches already-materialized windows. */
    WeekTemplateResult replaceWeekTemplate(WeekTemplateReplaceCommand command);

    /** @return true when executed now, false when an identical replay returned the first
     *     receipt; the adapter maps that flag to 201 vs 200 (23号 §6). */
    boolean createStaffWindowOutcome(CreateStaffWindowCommand command, StaffWindowResult[] out);

    StaffWindowResult updateStaffWindow(UpdateStaffWindowCommand command);

    StaffWindowResult closeStaffWindow(StaffWindowCloseCommand command);

    StaffWindowResult openStaffWindow(StaffWindowOpenCommand command);

    CapabilityView getCapabilities(CapabilityQuery query);

    CapabilityResult replaceCapabilities(ReplaceCapabilitiesCommand command);
}
