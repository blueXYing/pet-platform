package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.api.command.ServiceFlashPriceReservationApi;
import com.petplatform.service.api.error.ServiceFlashPriceApiCodes;
import com.petplatform.service.biz.application.FlashPriceReservationService;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Reserve face. The enabled bean delegates to the reservation service; the {@link #disabled()}
 * variant is assembled while {@code pet.service.flash-price.enabled=false} so the order kernel
 * gets a fail-closed FLASH_PRICE_DISABLED (503) answer instead of a missing bean — the order
 * then walks the LIST path (contract 77 §1).
 */
public final class ServiceFlashPriceReservationApiImpl implements ServiceFlashPriceReservationApi {

    private final FlashPriceReservationService service;
    private final boolean enabled;

    public ServiceFlashPriceReservationApiImpl(FlashPriceReservationService service) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.enabled = true;
    }

    /** Composition entry for the boot assembler. */
    public ServiceFlashPriceReservationApiImpl(
            DataSource dataSource, SnowflakeIdGenerator ids, Clock clock) {
        this(new FlashPriceReservationService(
                new FlashPriceStore(dataSource, ids),
                clock == null ? Clock.systemUTC() : clock));
    }

    private ServiceFlashPriceReservationApiImpl() {
        this.service = null;
        this.enabled = false;
    }

    /** Switch-off stub: reserve attempts fail closed (503 FLASH_PRICE_DISABLED). */
    public static ServiceFlashPriceReservationApiImpl disabled() {
        return new ServiceFlashPriceReservationApiImpl();
    }

    @Override
    public ReservationResult reserve(ReserveCommand command) {
        if (!enabled) {
            throw new ApiException(ServiceFlashPriceApiCodes.FLASH_PRICE_DISABLED,
                    "服务限时秒杀未开启，订单按门市价创建");
        }
        return service.reserve(command);
    }
}
