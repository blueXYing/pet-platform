package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import com.petplatform.merchant.biz.infrastructure.provider.TencentMapValidationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MerchantMapValidationConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(MerchantMapValidationConfiguration.class);

  @Test
  void mapProviderIsOffByDefault() {
    context.run(
        result -> {
          assertNull(result.getStartupFailure());
          assertTrue(result.getBeansOfType(MapValidationPort.class).isEmpty());
        });
  }

  @Test
  void enabledProviderRequiresKeyAndExplicitDistancePolicy() {
    context
        .withPropertyValues("pet.merchant.map.enabled=true")
        .run(result -> assertNotNull(result.getStartupFailure()));
  }

  @Test
  void enabledProviderBuildsOnlyForExplicitConfiguration() {
    context
        .withPropertyValues(
            "pet.merchant.map.enabled=true",
            "pet.merchant.map.endpoint=http://127.0.0.1:19876",
            "pet.merchant.map.timeout-millis=500",
            "pet.merchant.map.max-distance-meters=1000",
            "TMAP_WEBSERVICE_KEY=qa-key")
        .run(
            result -> {
              assertNull(result.getStartupFailure());
              assertInstanceOf(
                  TencentMapValidationProvider.class, result.getBean(MapValidationPort.class));
            });
  }
}
