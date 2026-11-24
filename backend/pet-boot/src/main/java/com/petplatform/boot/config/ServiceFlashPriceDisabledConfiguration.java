package com.petplatform.boot.config;

import com.petplatform.service.biz.apiimpl.ServiceFlashPriceReservationApiImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 77 §1 switch-off face: the reservation API is a fail-closed stub answering
 * FLASH_PRICE_DISABLED (503) so the order kernel degrades to the LIST path deterministically,
 * and no resolver/consumer/task/controller beans exist — reads never touch the flash tables.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "pet.service.flash-price",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
class ServiceFlashPriceDisabledConfiguration {

    @Bean
    ServiceFlashPriceReservationApiImpl serviceFlashPriceReservationApiDisabled() {
        return ServiceFlashPriceReservationApiImpl.disabled();
    }
}
