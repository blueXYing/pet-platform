package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import com.petplatform.merchant.biz.infrastructure.provider.LocationInputValidationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MerchantLocationInputValidationConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(MerchantLocationInputValidationConfiguration.class);

  @Test
  void validatorIsAbsentWhenMerchantApplicationIsDisabled() {
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
