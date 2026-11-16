package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.schedule.api.command.ScheduleMerchantCommandApi;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.ReplaceCapabilitiesCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;
/** Local implementation of the merchant schedule write contract; delegates to the command
 * service and exposes the created-vs-replayed flag for the 201/200 mapping (23号 §6).
 * Assembles its own persistence store so terminals never touch schedule persistence types
 * (ARCH-002; ReservationHoldApiImpl precedent). CCR-SCH-STAFF-OFFLINE-001 (2026-11-16)
 * removed the staff availability-window command family. */
public final class ScheduleMerchantCommandApiImpl implements ScheduleMerchantCommandApi {
    private final ScheduleMerchantCommandService commands;

    public ScheduleMerchantCommandApiImpl(ScheduleMerchantCommandService commands) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands is required");
    }

    /** Terminal assembly entry: the store, the command service and the receipt mapping are
     * wired inside the owning module from the shared DataSource and ID provider. */
    public ScheduleMerchantCommandApiImpl(javax.sql.DataSource source,
            com.petplatform.common.SnowflakeIdGenerator ids,
            com.petplatform.schedule.biz.application.ScheduleAdmissionGate admissions,
            com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi guard,
            com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi facts,
            com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi merchant,
            com.petplatform.service.api.query.ServiceQueryApi serviceFacts,
            ScheduleCapacityProofApiImpl proof, java.time.Clock clock) {
        this(new ScheduleMerchantCommandService(
                new com.petplatform.schedule.biz.infrastructure.persistence.ScheduleWriteStore(
                        source, ids),
                admissions, guard, facts, merchant, serviceFacts, proof, clock));
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
    public CapabilityView getCapabilities(CapabilityQuery query) {
        return commands.getCapabilities(query);
    }

    @Override
    public CapabilityResult replaceCapabilities(ReplaceCapabilitiesCommand command) {
        return commands.replaceCapabilities(command);
    }
}
