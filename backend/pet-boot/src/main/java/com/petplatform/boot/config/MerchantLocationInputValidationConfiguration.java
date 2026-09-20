package com.petplatform.boot.config;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import com.petplatform.merchant.biz.infrastructure.provider.LocationInputValidationProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

/** Default local input validator; it performs no map call or geographic restriction. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public class MerchantLocationInputValidationConfiguration {
  @Bean
  @ConditionalOnMissingBean(MapValidationPort.class)
  MapValidationPort merchantLocationInputValidation() {
    return new LocationInputValidationProvider();
  }
}
