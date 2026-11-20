package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.api.command.ServiceFlashPriceAdminApi;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.AdminReviewCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import com.petplatform.service.biz.application.FlashPriceAdminService;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Thin adapter: every rule lives in the application service (contract 77 §4). */
public final class ServiceFlashPriceAdminApiImpl implements ServiceFlashPriceAdminApi {

    private final FlashPriceAdminService service;

    public ServiceFlashPriceAdminApiImpl(FlashPriceAdminService service) {
        this.service = Objects.requireNonNull(service, "service is required");
    }

    /** Composition entry for the boot assembler; the store-facts port may stay null. */
    public ServiceFlashPriceAdminApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            FlashPriceAdminService.FlashPriceStoreFactsPort storeFacts,
            Clock clock) {
        this(new FlashPriceAdminService(
                new FlashPriceStore(dataSource, ids),
                storeFacts,
                clock == null ? Clock.systemUTC() : clock));
    }

    @Override
    public FlashPricePage list(String status, int page, int pageSize, QueryContext context) {
        return service.list(status, page, pageSize, context);
    }

    @Override
    public FlashPriceItem approve(AdminReviewCommand command) {
        return service.approve(command);
    }

    @Override
    public FlashPriceItem reject(AdminReviewCommand command) {
        return service.reject(command);
    }

    @Override
    public FlashPriceItem pause(AdminReviewCommand command) {
        return service.pause(command);
    }

    @Override
    public FlashPriceItem end(AdminReviewCommand command) {
        return service.end(command);
    }
}
