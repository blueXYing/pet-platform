package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.api.command.ServiceCategoryCommandApi;
import com.petplatform.service.api.dto.ServiceCategoryTypes.CreateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.DisableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.EnableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryResult;
import com.petplatform.service.api.dto.ServiceCategoryTypes.UpdateServiceCategoryCommand;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort;
import com.petplatform.service.biz.application.ServiceCategoryCommandService;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import java.time.Clock;
import javax.sql.DataSource;

/** Local implementation of the admin service category dictionary commands (B-SVC-02). */
public final class ServiceCategoryCommandApiImpl implements ServiceCategoryCommandApi {
    private final ServiceCategoryCommandService commands;

    public ServiceCategoryCommandApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            Clock clock,
            boolean allowHttpIconUrl,
            ServiceCategoryAuthorizationPort authorization) {
        this.commands =
                new ServiceCategoryCommandService(
                        new ServiceWriteStore(dataSource, ids),
                        clock,
                        allowHttpIconUrl,
                        authorization);
    }

    @Override
    public ServiceCategoryResult create(CreateServiceCategoryCommand command) {
        ServiceCategoryResult[] out = new ServiceCategoryResult[1];
        commands.createOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceCategoryResult update(UpdateServiceCategoryCommand command) {
        ServiceCategoryResult[] out = new ServiceCategoryResult[1];
        commands.updateOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceCategoryResult enable(EnableServiceCategoryCommand command) {
        ServiceCategoryResult[] out = new ServiceCategoryResult[1];
        commands.enableOutcome(command, out);
        return out[0];
    }

    @Override
    public ServiceCategoryResult disable(DisableServiceCategoryCommand command) {
        ServiceCategoryResult[] out = new ServiceCategoryResult[1];
        commands.disableOutcome(command, out);
        return out[0];
    }
}
