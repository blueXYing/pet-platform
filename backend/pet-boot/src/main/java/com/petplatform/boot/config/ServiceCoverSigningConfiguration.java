package com.petplatform.boot.config;

import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.thirdparty.api.ServiceCoverSigningApi;
import com.petplatform.thirdparty.biz.apiimpl.ServiceCoverSigningApiImpl;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.thirdparty.biz.infrastructure.oss.S3ServiceCoverObjectSigner;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit opt-in with the real private-upload pipeline; off/missing retains the existing 503. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.private-assets", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "pet.service.cover-signing", name = "enabled", havingValue = "true")
public class ServiceCoverSigningConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ServiceCoverObjectSigner.class)
  S3ServiceCoverObjectSigner serviceCoverObjectSigner(OssConnection connection,
      ObjectProvider<Clock> clocks, @Value("${pet.service.cover-signing.window-seconds}") long window) {
    return new S3ServiceCoverObjectSigner(connection, clocks.getIfAvailable(Clock::systemUTC), window);
  }

  @Bean
  @ConditionalOnMissingBean(ServiceCoverSigningApi.class)
  ServiceCoverSigningApi serviceCoverSigningApi(DataSource source, ServiceCoverObjectSigner signer,
      ObjectProvider<Clock> clocks) {
    return new ServiceCoverSigningApiImpl(source, signer, clocks.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean(ServiceCoverUrlPort.class)
  ServiceCoverUrlPort serviceCoverUrls(ServiceCoverSigningApi covers) {
    return assetId -> {
      var result = covers.signServiceCover(assetId,
          new QueryContext(UUID.randomUUID().toString(), OperatorType.SYSTEM, "service-cover-display"));
      return new ServiceCoverUrlPort.CoverUrl(result.assetId(), result.signedUrl(), result.expiresAt().getEpochSecond());
    };
  }
}
