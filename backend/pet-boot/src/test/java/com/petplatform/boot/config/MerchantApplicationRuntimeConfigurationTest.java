package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.merchant.api.command.MerchantApplicationCommandApi;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MerchantApplicationRuntimeConfigurationTest {

  private static final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(MerchantApplicationRuntimeConfiguration.class)
          // Ruling 35 (2026-10-16) flips the platform capability switches to default ON;
          // the fail-closed assembly checks close the switch explicitly.
          .withPropertyValues("pet.merchant.application.enabled=false");

  @Test
  void explicitOffKeepsDomainAssemblyUnassembledWithoutStartingAnyDependencies() {
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
