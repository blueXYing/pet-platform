package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import com.petplatform.merchant.biz.infrastructure.provider.LocationInputValidationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MerchantLocationInputValidationConfigurationTest {
  // Ruling 35 (2026-10-16) flips the platform capability switches to default ON; the
  // fail-closed check closes the switch explicitly.
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(MerchantLocationInputValidationConfiguration.class)
          .withPropertyValues("pet.merchant.application.enabled=false");

  @Test
  void validatorIsAbsentWhenMerchantApplicationIsExplicitlyDisabled() {
    context.run(
        result -> {
          assertNull(result.getStartupFailure());
          assertTrue(result.getBeansOfType(MapValidationPort.class).isEmpty());
        });
  }

  @Test
  void merchantApplicationGetsLocalValidatorWithoutMapKeyOrExternalProvider() {
    context
        .withPropertyValues("pet.merchant.application.enabled=true")
        .run(
            result -> {
              assertNull(result.getStartupFailure());
              assertInstanceOf(
                  LocationInputValidationProvider.class, result.getBean(MapValidationPort.class));
            });
  }
}
