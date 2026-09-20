package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.boot.adapter.web.merchant.*;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import com.petplatform.merchant.biz.apiimpl.MerchantApplicationApiImpl;
import com.petplatform.merchant.biz.application.*;
import com.petplatform.task.core.*;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.application.port.*;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PrivateAssetRuntimeConfigurationTest {
  @Test
  void privateHttpAndRuntimeAreAbsentByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            PrivateAssetRuntimeConfiguration.class,
            CPrivateAssetController.class,
            AdminPrivateAssetController.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBeansOfType(PrivateAssetApi.class).isEmpty());
              assertTrue(context.getBeansOfType(CPrivateAssetController.class).isEmpty());
              assertTrue(context.getBeansOfType(AdminPrivateAssetController.class).isEmpty());
            });
  }

  @Test
  void enabledRuntimeFailsClosedWhenProvidersAreMissing() {
    new ApplicationContextRunner()
        .withUserConfiguration(PrivateAssetRuntimeConfiguration.class)
        .withPropertyValues("pet.private-assets.enabled=true")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void enabledRuntimeBuildsCoreMerchantBridgeWorkerAndControllersWithoutACycle() {
    AtomicLong ids = new AtomicLong(10_000);
    String grant = Base64.getEncoder().encodeToString(key(11));
    String reason = Base64.getEncoder().encodeToString(key(17));
    new ApplicationContextRunner()
        .withUserConfiguration(
            PrivateAssetRuntimeConfiguration.class,
            MerchantApplicationRuntimeConfiguration.class,
            CPrivateAssetController.class,
            AdminPrivateAssetController.class)
        .withPropertyValues(
            "pet.private-assets.enabled=true",
            "pet.merchant.application.enabled=true",
            "PRIVATE_ASSET_GRANT_KEY_VERSION=qa-grant-v1",
            "PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64=" + grant,
            "PRIVATE_ASSET_REASON_KEY_VERSION=qa-reason-v1",
            "PRIVATE_ASSET_REASON_AES_KEY_BASE64=" + reason,
            "pet.private-assets.worker-owner=qa-private-worker")
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .withBean(SnowflakeIdGenerator.class, () -> ids::incrementAndGet)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(PrivateObjectStore.class, () -> mock(PrivateObjectStore.class))
        .withBean(PrivateAssetScanner.class, () -> mock(PrivateAssetScanner.class))
        .withBean(PrivateAssetImageNormalizer.class, () -> mock(PrivateAssetImageNormalizer.class))
        .withBean(
            PrivateAssetWatermarkRenderer.class, () -> mock(PrivateAssetWatermarkRenderer.class))
        .withBean(AdminAuthorizationQueryApi.class, () -> mock(AdminAuthorizationQueryApi.class))
        .withBean(ApplicationValidationPorts.OpenCityReader.class, () -> city -> true)
        .withBean(
            ApplicationValidationPorts.MapValidationPort.class,
            () -> (city, address, longitude, latitude) -> true)
        .withBean(
            ApplicationValidationPorts.ProtectedValuePort.class,
            () -> mock(ApplicationValidationPorts.ProtectedValuePort.class))
        .withBean(SubjectCredentialPort.class, () -> mock(SubjectCredentialPort.class))
        .withBean(IntegrationEventPublisher.class, () -> mock(IntegrationEventPublisher.class))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertInstanceOf(PrivateAssetApiImpl.class, context.getBean(PrivateAssetApi.class));
              assertInstanceOf(
                  MerchantApplicationApiImpl.class,
                  context.getBean(MerchantApplicationQueryApi.class));
              assertNotNull(context.getBean(PrivateAssetQueryPort.class));
              assertNotNull(context.getBean(AsyncTaskWorker.class));
              assertEquals(1, context.getBeansOfType(TaskRegistration.class).size());
              assertNotNull(context.getBean(CPrivateAssetController.class));
              assertNotNull(context.getBean(AdminPrivateAssetController.class));
            });
  }

  private static byte[] key(int fill) {
    byte[] value = new byte[32];
    java.util.Arrays.fill(value, (byte) fill);
    return value;
  }
}
