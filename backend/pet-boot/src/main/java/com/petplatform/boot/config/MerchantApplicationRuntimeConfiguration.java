package com.petplatform.boot.config;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.biz.apiimpl.MerchantAgreementApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantApplicationApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantQueryApiImpl;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.MerchantAgreementEligibilityFactsAdapter;
import com.petplatform.merchant.biz.application.MerchantApplicationDependencies;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit internal-domain composition. No HTTP route, schema migration, credential provider, or
 * fixture is supplied here. Enabling requires every real dependency bean to exist.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public class MerchantApplicationRuntimeConfiguration {
  @Bean
  ApplicationFinalAuthorizationPort merchantApplicationAuthorization(
      AdminAuthorizationQueryApi authorization) {
    return new MerchantApplicationAuthorizationAdapter(authorization);
  }

  @Bean
  MerchantApplicationDependencies merchantApplicationDependencies(
      PrivateAssetQueryPort assets,
      ApplicationValidationPorts.OpenCityReader cities,
      ApplicationValidationPorts.MapValidationPort maps,
      ApplicationValidationPorts.ProtectedValuePort protectedValues,
      SubjectCredentialPort credentials,
      ApplicationFinalAuthorizationPort authorization,
      IntegrationEventPublisher publisher) {
    return new MerchantApplicationDependencies(
        assets, cities, maps, protectedValues, credentials, authorization, publisher);
  }

  @Bean
  MerchantApplicationApiImpl merchantApplicationApi(
      DataSource source,
      SnowflakeIdGenerator ids,
      MerchantApplicationDependencies dependencies,
      ObjectProvider<Clock> clock) {
    return new MerchantApplicationApiImpl(
        source, ids, clock.getIfAvailable(Clock::systemUTC), dependencies);
  }

  @Bean
  ApplicationReviewFactsReader merchantApplicationReviewFacts(
      DataSource source, SnowflakeIdGenerator ids) {
    return new PersistentApplicationReviewFactsReader(source, ids);
  }

  @Bean
  MerchantAgreementApiImpl merchantAgreementApi(
      DataSource source,
      SnowflakeIdGenerator ids,
      ApplicationReviewFactsReader applications,
      ObjectProvider<Clock> clock) {
    return new MerchantAgreementApiImpl(
        source, ids, clock.getIfAvailable(Clock::systemUTC), applications);
  }

  @Bean
  MerchantQueryApiImpl merchantQueryApi(
      DataSource source, ApplicationReviewFactsReader applications) {
    return new MerchantQueryApiImpl(
        source, new MerchantAgreementEligibilityFactsAdapter(source, applications));
  }
}
