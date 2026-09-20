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
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit domain and HTTP composition. No schema migration, credential provider, or fixture is
 * supplied here. Enabling requires every real dependency bean to exist.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public class MerchantApplicationRuntimeConfiguration {
  @Bean
  @ConditionalOnMissingBean(SubjectCredentialPort.class)
  @ConditionalOnProperty(prefix = "pet.merchant.subject", name = "enabled", havingValue = "true")
  SubjectCredentialPort merchantSubjects(ApplicationValidationPorts.ProtectedValuePort protection,
      @Value("${MERCHANT_SUBJECT_POLICY_VERSION:}") String version,
      @Value("${MERCHANT_SUBJECT_HMAC_KEY_BASE64:}") String lookup,
      @Value("${MERCHANT_PROTECTED_AES_KEY_BASE64:}") String aes,
      @Value("${MERCHANT_PROTECTED_HMAC_KEY_BASE64:}") String hmac) {
    byte[] key = null, encryption = null, equality = null;
    try {
      key = java.util.Base64.getDecoder().decode(lookup);
      encryption = java.util.Base64.getDecoder().decode(aes);
      equality = java.util.Base64.getDecoder().decode(hmac);
      if (java.security.MessageDigest.isEqual(key, encryption)
          || java.security.MessageDigest.isEqual(key, equality))
        throw new IllegalArgumentException("keys must be independent");
      return new MainlandSubjectCredentialProvider(protection, version, key);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Explicit independent subject lookup key and pinned policy are required");
    } finally {
      if (key != null) java.util.Arrays.fill(key, (byte) 0);
      if (encryption != null) java.util.Arrays.fill(encryption, (byte) 0);
      if (equality != null) java.util.Arrays.fill(equality, (byte) 0);
    }
  }
  @Bean
  @ConditionalOnMissingBean(ApplicationValidationPorts.ProtectedValuePort.class)
  @ConditionalOnProperty(prefix = "pet.merchant.protection", name = "enabled", havingValue = "true")
  ApplicationValidationPorts.ProtectedValuePort merchantProtectedValues(
      @Value("${MERCHANT_PROTECTED_KEY_VERSION:}") String version,
      @Value("${MERCHANT_PROTECTED_AES_KEY_BASE64:}") String aes,
      @Value("${MERCHANT_PROTECTED_HMAC_KEY_BASE64:}") String hmac) {
    byte[] encryption = null, equality = null;
    try {
      encryption = java.util.Base64.getDecoder().decode(aes);
      equality = java.util.Base64.getDecoder().decode(hmac);
      return new AesGcmProtectedValueProvider(version, encryption, equality);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Explicit separate merchant protection keys and pinned version are required");
    } finally {
      if (encryption != null) java.util.Arrays.fill(encryption, (byte) 0);
      if (equality != null) java.util.Arrays.fill(equality, (byte) 0);
    }
  }
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
