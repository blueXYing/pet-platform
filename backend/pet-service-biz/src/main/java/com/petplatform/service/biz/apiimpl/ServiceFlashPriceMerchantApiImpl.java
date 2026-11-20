package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.service.api.command.ServiceFlashPriceMerchantApi;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CancelCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CreateFlashPriceCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.SubmitCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.UpdateFlashPriceCommand;
import com.petplatform.service.biz.application.FlashPriceMerchantService;
import com.petplatform.service.biz.application.ServiceAdmissionGate;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Thin adapter: every rule lives in the application service (contract 77 §3). */
public final class ServiceFlashPriceMerchantApiImpl implements ServiceFlashPriceMerchantApi {

    private final FlashPriceMerchantService service;

    public ServiceFlashPriceMerchantApiImpl(FlashPriceMerchantService service) {
        this.service = Objects.requireNonNull(service, "service is required");
    }

    /** Composition entry for the boot assembler (mirrors ServiceCommandApiImpl). */
    public ServiceFlashPriceMerchantApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            Clock clock) {
        this(new FlashPriceMerchantService(
                new FlashPriceStore(dataSource, ids),
                new ServiceAdmissionGate(admissions),
                clock == null ? Clock.systemUTC() : clock));
    }

    @Override
    public FlashPricePage list(
            String serviceId, String status, int page, int pageSize, QueryContext context) {
        return service.list(serviceId, status, page, pageSize, context);
    }

    @Override
    public FlashPriceItem create(CreateFlashPriceCommand command) {
        return service.create(command);
    }

    @Override
    public FlashPriceItem update(UpdateFlashPriceCommand command) {
        return service.update(command);
    }

    @Override
    public FlashPriceItem submit(SubmitCommand command) {
        return service.submit(command);
    }

    @Override
    public FlashPriceItem cancel(CancelCommand command) {
        return service.cancel(command);
    }
}
