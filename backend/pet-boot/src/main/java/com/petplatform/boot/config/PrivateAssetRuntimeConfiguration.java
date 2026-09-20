package com.petplatform.boot.config;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.task.core.*;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.PrivateAssetReadAuthorizer;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetReconcileTaskHandler;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.thirdparty.biz.infrastructure.oss.S3PrivateObjectStore;
import com.petplatform.thirdparty.biz.infrastructure.provider.assetimage.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

/** Optional composition bridges. Off by default and absent unless the real owner API is present. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.private-assets", name = "enabled", havingValue = "true")
public class PrivateAssetRuntimeConfiguration {
  @Bean
  PrivateAssetResponseHeadersFilter privateAssetResponseHeadersFilter() {
    return new PrivateAssetResponseHeadersFilter();
  }

  @Bean
  @ConditionalOnMissingBean({OssConnection.class, PrivateObjectStore.class})
  OssConnection privateAssetOssConnection() {
    return OssConnection.fromEnv();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(PrivateObjectStore.class)
  S3PrivateObjectStore privateObjectStore(OssConnection connection) {
    return new S3PrivateObjectStore(connection);
  }

  @Bean
  @ConditionalOnMissingBean({PrivateAssetGrantKeyProvider.class, PrivateAssetReasonProtector.class})
  PrivateAssetSecrets privateAssetSecrets(
      @Value("${PRIVATE_ASSET_GRANT_KEY_VERSION:}") String grantVersion,
      @Value("${PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64:}") String grantBase64,
      @Value("${PRIVATE_ASSET_REASON_KEY_VERSION:}") String reasonVersion,
      @Value("${PRIVATE_ASSET_REASON_AES_KEY_BASE64:}") String reasonBase64) {
    return PrivateAssetSecrets.from(grantVersion, grantBase64, reasonVersion, reasonBase64);
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetGrantKeyProvider.class)
  PrivateAssetGrantKeyProvider privateAssetGrantKeys(PrivateAssetSecrets secrets) {
    return secrets::grantKey;
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetReasonProtector.class)
  PrivateAssetReasonProtector privateAssetReasons(PrivateAssetSecrets secrets) {
    return secrets::protectReason;
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetScanner.class)
  PrivateAssetScanner privateAssetScanner(
      @Value("${PRIVATE_ASSET_CLAMAV_HOST:}") String host,
      @Value("${PRIVATE_ASSET_CLAMAV_PORT:0}") int port,
      @Value("${PRIVATE_ASSET_CLAMAV_TIMEOUT_MILLIS:0}") long timeoutMillis) {
    // The provider validates a positive endpoint and a hard per-exchange deadline <= 60 seconds.
    return new ClamAvPrivateAssetScanner(host, port, Duration.ofMillis(timeoutMillis));
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetImageNormalizer.class)
  PrivateAssetImageNormalizer privateAssetImageNormalizer() {
    return new ImageIoPrivateAssetImageNormalizer();
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetWatermarkRenderer.class)
  PrivateAssetWatermarkRenderer privateAssetWatermarkRenderer() {
    return new ImageIoPrivateAssetWatermarkRenderer();
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetQueryPort.class)
  PrivateAssetQueryPort merchantPrivateAssets(PrivateAssetApi assets) {
    return new MerchantPrivateAssetQueryAdapter(assets);
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetReadAuthorizer.class)
  PrivateAssetReadAuthorizer privateAssetReadAuthorizer(
      ObjectProvider<MerchantApplicationQueryApi> merchants,
      AdminAuthorizationQueryApi authorization) {
    // Delayed resolution breaks the intentional composition cycle:
    // private API -> authorizer -> MER query -> MER dependencies -> private query port -> private
    // API.
    return new PrivateAssetReadAuthorizationAdapter(merchants::getObject, authorization);
  }

  @Bean
  @ConditionalOnMissingBean(PrivateAssetApi.class)
  PrivateAssetApiImpl privateAssetApi(
      DataSource source,
      SnowflakeIdGenerator ids,
      PrivateObjectStore objects,
      PrivateAssetScanner scanner,
      PrivateAssetImageNormalizer normalizer,
      PrivateAssetWatermarkRenderer watermarks,
      PrivateAssetReadAuthorizer authorizer,
      PrivateAssetGrantKeyProvider grantKeys,
      PrivateAssetReasonProtector reasons,
      ObjectProvider<Clock> clock) {
    return new PrivateAssetApiImpl(
        source,
        ids,
        objects,
        scanner,
        normalizer,
        watermarks,
        authorizer,
        grantKeys,
        reasons,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  TaskRegistration<Long> privateAssetReconcileRegistration(PrivateAssetApiImpl assets) {
    return new PrivateAssetReconcileTaskHandler(assets)
        .registration(new com.fasterxml.jackson.databind.ObjectMapper());
  }

  /**
   * The process owns one SQL13 worker over the complete registration collection. Future handlers
   * join this collection; private assets do not run a competing module-specific poller.
   */
  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean(AsyncTaskWorker.class)
  AsyncTaskWorker asyncTaskWorker(
      DataSource source,
      SnowflakeIdGenerator ids,
      Collection<TaskRegistration<?>> registrations,
      ObjectProvider<Clock> clock,
      @Value("${pet.private-assets.worker-owner:boot-private-asset-1}") String owner) {
    return AsyncTaskWorker.create(
        source,
        ids,
        owner,
        clock.getIfAvailable(Clock::systemUTC),
        TaskWorkerSettings.defaults(),
        new TaskRetryDelays(
            java.util.Map.of(
                "FAST_INTERNAL",
                java.util.List.of(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(10)))),
        registrations);
  }

  static final class PrivateAssetSecrets {
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private final String grantVersion, reasonVersion;
    private final SecretKeySpec grantKey, reasonKey;

    private PrivateAssetSecrets(
        String grantVersion, byte[] grantKey, String reasonVersion, byte[] reasonKey) {
      this.grantVersion = grantVersion;
      this.reasonVersion = reasonVersion;
      this.grantKey = new SecretKeySpec(grantKey, "HmacSHA256");
      this.reasonKey = new SecretKeySpec(reasonKey, "AES");
    }

    static PrivateAssetSecrets from(
        String grantVersion, String grantBase64, String reasonVersion, String reasonBase64) {
      byte[] grant = null, reason = null;
      try {
        if (grantVersion == null
            || !grantVersion.matches("[A-Za-z0-9._-]{1,64}")
            || reasonVersion == null
            || !reasonVersion.matches("[A-Za-z0-9._-]{1,64}")) {
          throw new IllegalArgumentException("explicit private asset key versions are required");
        }
        grant = java.util.Base64.getDecoder().decode(grantBase64);
        reason = java.util.Base64.getDecoder().decode(reasonBase64);
        if (grant.length != 32
            || reason.length != 32
            || java.security.MessageDigest.isEqual(grant, reason)) {
          throw new IllegalArgumentException("independent 256-bit private asset keys are required");
        }
        return new PrivateAssetSecrets(grantVersion, grant, reasonVersion, reason);
      } catch (RuntimeException invalid) {
        throw new IllegalStateException("Explicit independent private asset keys are required");
      } finally {
        if (grant != null) java.util.Arrays.fill(grant, (byte) 0);
        if (reason != null) java.util.Arrays.fill(reason, (byte) 0);
      }
    }

    PrivateAssetGrantKeyProvider.KeyMaterial grantKey() {
      return new PrivateAssetGrantKeyProvider.KeyMaterial(grantVersion, grantKey);
    }

    byte[] protectReason(String purpose, String plaintext) {
      if (!"private-asset-read-reason".equals(purpose)
          || plaintext == null
          || plaintext.isBlank()
          || plaintext.length() > 500) {
        throw new IllegalArgumentException("invalid private asset reason");
      }
      try {
        byte[] version = reasonVersion.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, reasonKey, new GCMParameterSpec(128, iv));
        cipher.updateAAD(
            (purpose + ":" + reasonVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ciphertext =
            cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var out = java.nio.ByteBuffer.allocate(1 + version.length + iv.length + ciphertext.length);
        out.put((byte) version.length).put(version).put(iv).put(ciphertext);
        return out.array();
      } catch (Exception failure) {
        throw new IllegalStateException("private asset reason protection unavailable");
      }
    }
  }
}
