package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.merchant.api.command.MerchantApplicationCommandApi;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MerchantApplicationRuntimeConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(MerchantApplicationRuntimeConfiguration.class);

  @Test
  void domainAssemblyIsDisabledByDefaultWithoutStartingAnyDependencies() {
    context.run(
        ctx -> {
          assertNull(ctx.getStartupFailure());
          assertTrue(ctx.getBeansOfType(MerchantApplicationCommandApi.class).isEmpty());
          assertTrue(ctx.getBeansOfType(ApplicationFinalAuthorizationPort.class).isEmpty());
        });
  }

  @Test
  void enablingWithoutAuthoritativeProvidersCannotConstructASuccessfulRuntime() {
    context
        .withPropertyValues("pet.merchant.application.enabled=true")
        .run(ctx -> assertNotNull(ctx.getStartupFailure()));
  }
}
