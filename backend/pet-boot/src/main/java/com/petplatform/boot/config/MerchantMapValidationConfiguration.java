package com.petplatform.boot.config;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import com.petplatform.merchant.biz.infrastructure.provider.TencentMapValidationProvider;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

/** Explicit real map-provider assembly. Disabled unless the merchant application slice opts in. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.map", name = "enabled", havingValue = "true")
public class MerchantMapValidationConfiguration {
  @Bean
  @ConditionalOnMissingBean(MapValidationPort.class)
  MapValidationPort merchantMapValidation(
      @Value("${pet.merchant.map.endpoint:https://apis.map.qq.com}") String endpoint,
      @Value("${TMAP_WEBSERVICE_KEY:}") String key,
      @Value("${pet.merchant.map.timeout-millis:3000}") long timeoutMillis,
      @Value("${pet.merchant.map.max-distance-meters:0}") double maxDistanceMeters) {
    return new TencentMapValidationProvider(
        endpoint, key, Duration.ofMillis(timeoutMillis), maxDistanceMeters);
  }
}
