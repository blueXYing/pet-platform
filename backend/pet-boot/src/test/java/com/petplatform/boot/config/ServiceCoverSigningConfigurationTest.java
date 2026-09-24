package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.thirdparty.api.ServiceCoverSigningApi;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ServiceCoverSigningConfigurationTest {
  @Test void absentByDefaultAndRequiresBothExplicitSwitches() {
    for (String[] properties : new String[][] {{}, {"pet.private-assets.enabled=true"}, {"pet.service.cover-signing.enabled=true"}}) {
      new ApplicationContextRunner().withUserConfiguration(ServiceCoverSigningConfiguration.class)
          .withPropertyValues(properties).run(c -> {
            assertNull(c.getStartupFailure()); assertTrue(c.getBeansOfType(ServiceCoverUrlPort.class).isEmpty());
          });
    }
  }

  @Test void adapterUsesApprovedOwnerApiAndPreservesExpiry() {
    Instant expiry = Instant.now().plusSeconds(600);
    new ApplicationContextRunner().withUserConfiguration(ServiceCoverSigningConfiguration.class)
        .withPropertyValues("pet.private-assets.enabled=true", "pet.service.cover-signing.enabled=true")
        .withBean(ServiceCoverObjectSigner.class, () -> mock(ServiceCoverObjectSigner.class))
        .withBean(ServiceCoverSigningApi.class, () -> (id, query) -> new ServiceCoverSigningApi.SignedServiceCover(id, "https://test.example.invalid/x", expiry))
        .run(c -> {
          assertNull(c.getStartupFailure());
          var result = c.getBean(ServiceCoverUrlPort.class).sign("101");
          assertEquals("101", result.coverAssetId()); assertEquals(expiry.getEpochSecond(), result.expiresAtEpochSeconds());
        });
  }
}
