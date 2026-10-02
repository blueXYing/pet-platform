package com.petplatform.schedule.api.command;

import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseResult;
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

    /** @return true when executed now, false when an identical replay returned the first
     *     receipt; the adapter maps that flag to 201 vs 200 (23号 §6). */
    boolean createStaffWindowOutcome(CreateStaffWindowCommand command, StaffWindowResult[] out);

    StaffWindowResult updateStaffWindow(UpdateStaffWindowCommand command);

    StaffWindowResult closeStaffWindow(StaffWindowCloseCommand command);

    StaffWindowResult openStaffWindow(StaffWindowOpenCommand command);

    CapabilityView getCapabilities(CapabilityQuery query);

    CapabilityResult replaceCapabilities(ReplaceCapabilitiesCommand command);
}
