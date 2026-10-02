package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.schedule.api.command.ScheduleMerchantCommandApi;
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
/** Local implementation of the merchant schedule write contract; delegates to the command
 * service and exposes the created-vs-replayed flag for the 201/200 mapping (23号 §6). */
public final class ScheduleMerchantCommandApiImpl implements ScheduleMerchantCommandApi {
    private final ScheduleMerchantCommandService commands;

    public ScheduleMerchantCommandApiImpl(ScheduleMerchantCommandService commands) {
        this.commands = commands;
    }

    @Override
    public boolean createWindowOutcome(CreateWindowCommand command, WindowResult[] out) {
        return commands.createWindowOutcome(command, out);
    }

    @Override
    public WindowResult updateWindow(UpdateWindowCommand command) {
        return commands.updateWindow(command);
    }

    @Override
    public WindowResult closeWindow(WindowCloseCommand command) {
        return commands.closeWindow(command);
    }

    @Override
    public WindowResult openWindow(WindowOpenCommand command) {
        return commands.openWindow(command);
    }

    @Override
    public BatchCloseResult batchClose(BatchCloseCommand command) {
        return commands.batchClose(command);
    }

    @Override
    public boolean createStaffWindowOutcome(CreateStaffWindowCommand command,
            StaffWindowResult[] out) {
        return commands.createStaffWindowOutcome(command, out);
    }

    @Override
    public StaffWindowResult updateStaffWindow(UpdateStaffWindowCommand command) {
        return commands.updateStaffWindow(command);
    }

    @Override
    public StaffWindowResult closeStaffWindow(StaffWindowCloseCommand command) {
        return commands.closeStaffWindow(command);
    }

    @Override
    public StaffWindowResult openStaffWindow(StaffWindowOpenCommand command) {
        return commands.openStaffWindow(command);
    }

    @Override
    public CapabilityView getCapabilities(CapabilityQuery query) {
        return commands.getCapabilities(query);
    }

    @Override
    public CapabilityResult replaceCapabilities(ReplaceCapabilitiesCommand command) {
        return commands.replaceCapabilities(command);
    }
}
