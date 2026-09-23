package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.service.api.command.ServiceCommandApi;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemResult;
import com.petplatform.service.api.dto.ServiceWriteTypes.CreateServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.DecideServiceReviewCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ForceOfflineServiceCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.SubmitServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.TakeServiceOfflineCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.UpdateServiceItemCommand;
import com.petplatform.service.biz.application.ServiceAdmissionGate;
import com.petplatform.service.biz.application.ServiceCommandService;
import com.petplatform.service.biz.application.ServiceWriteDependencies;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import java.time.Clock;
import javax.sql.DataSource;

/** Local implementation of the approved service write command contract. */
public final class ServiceCommandApiImpl implements ServiceCommandApi {
    private final ServiceCommandService commands;

    public ServiceCommandApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            ServiceWriteDependencies deps,
            Clock clock) {
        this.commands =
                new ServiceCommandService(
                        new ServiceWriteStore(dataSource, ids),
                        new ServiceAdmissionGate(admissions),
                        deps,
                        clock);
    }

    /** Exposes the created-vs-replayed flag so the HTTP adapter can answer 201 vs 200 (23号 §6). */
    public boolean createDraftOutcome(CreateServiceItemCommand command, ServiceItemResult[] out) {
        return commands.createOutcome(command, out);
    }

    @Override
    public ServiceItemResult createDraft(CreateServiceItemCommand command) {
        ServiceItemResult[] out = new ServiceItemResult[1];
        commands.createOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceItemResult update(UpdateServiceItemCommand command) {
        ServiceItemResult[] out = new ServiceItemResult[1];
        commands.updateOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceItemResult submitForReview(SubmitServiceItemCommand command) {
        ServiceItemResult[] out = new ServiceItemResult[1];
        commands.submitOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceItemResult takeOffline(TakeServiceOfflineCommand command) {
        ServiceItemResult[] out = new ServiceItemResult[1];
        commands.offlineOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceItemResult decideReview(DecideServiceReviewCommand command) {
        ServiceItemResult[] out = new ServiceItemResult[1];
        commands.decideOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceItemResult forceOffline(ForceOfflineServiceCommand command) {
        ServiceItemResult[] out = new ServiceItemResult[1];
        commands.forceOfflineOutcome(command, out);
        return out[0];
    }
}
