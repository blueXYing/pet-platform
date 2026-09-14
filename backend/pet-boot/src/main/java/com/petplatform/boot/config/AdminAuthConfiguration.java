package com.petplatform.boot.config;

import com.petplatform.admin.biz.application.*;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.admin.biz.task.*;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminAuthProperties.class)
@ConditionalOnProperty(prefix = "pet.auth.admin", name = "enabled", havingValue = "true")
@EnableScheduling
public class AdminAuthConfiguration {
  @Bean
  org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer adminStrictJson() {
    return builder ->
        builder.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock adminClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(SnowflakeIdGenerator.class)
  HutoolSnowflakeIdProvider adminIdProvider(
      DataSource source, AdminAuthProperties p, ObjectProvider<PreviousJvmExitVerifier> verifier) {
    return new HutoolSnowflakeIdProvider(
        new JdbcSnowflakeNodeStore(source),
        new SnowflakeProviderSettings(p.getNodeId()),
        verifier.getIfAvailable(PreviousJvmExitVerifier::rejecting));
  }

  @Bean
  @ConditionalOnMissingBean
  AdminPasswordHasher adminPasswordHasher() {
    return new AdminPasswordHasher();
  }

  @Bean
  @ConditionalOnMissingBean
  AdminSecretCodec adminSecretCodec(AdminAuthProperties p) {
    try {
      return AdminSecretCodec.fixed(
          p.getKeyId(),
          Base64.getDecoder().decode(p.getMacKeyBase64()),
          Base64.getDecoder().decode(p.getEncryptionKeyBase64()));
    } catch (RuntimeException e) {
      throw new IllegalStateException("Explicit separate auth secrets must be configured");
    }
  }

  @Bean
  @ConditionalOnMissingBean
  AdminGrantCache adminGrantCache(AdminAuthProperties p) {
    return new RedisAdminGrantCache(
        p.getRedisHost(),
        p.getRedisPort(),
        p.getRedisUsername(),
        p.getRedisPassword() == null ? null : p.getRedisPassword().toCharArray(),
        p.getCachePrefix());
  }

  @Bean
  AdminAuthService adminAuthService(
      DataSource source,
      SnowflakeIdGenerator ids,
      Clock clock,
      AdminPasswordHasher passwords,
      AdminSecretCodec secrets,
      AdminGrantCache cache,
      AdminAuthProperties p) {
    if (p.getOrigin() == null || !p.getOrigin().matches("https?://[^/]+"))
      throw new IllegalStateException("Explicit trusted admin origin required");
    java.net.URI origin = java.net.URI.create(p.getOrigin());
    if (origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || (!"https".equals(origin.getScheme())
            && !java.util.Set.of("127.0.0.1", "localhost").contains(origin.getHost())))
      throw new IllegalStateException("HTTPS origin or isolated loopback origin required");
    if (p.isMigrationEnabled()) {
      try (var c = source.getConnection()) {
        if (p.getMigrationDatabase() == null
            || !p.getMigrationDatabase().startsWith("auth001_")
            || !p.getMigrationDatabase().equals(c.getCatalog()))
          throw new IllegalStateException(
              "Only explicit isolated admin-auth migration is permitted in this delivery");
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException("Admin migration target unavailable");
      }
      Flyway.configure()
          .dataSource(source)
          .locations("classpath:db/admin-auth-migration")
          .table("admin_auth_schema_history")
          .load()
          .migrate();
    }
    return new AdminAuthService(source, ids, clock, passwords, secrets, cache);
  }

  @Bean
  @ConditionalOnMissingBean
  AdminAuditSink adminAuditSink(AdminAuthProperties p) {
    if (p.getAuditPath() == null || p.getAuditPath().isBlank())
      throw new IllegalStateException("Explicit durable audit sink path required");
    return new FileAdminAuditSink(Path.of(p.getAuditPath()));
  }

  @Bean
  AdminAuditDelivery adminAuditDelivery(DataSource source, AdminAuditSink sink) {
    return new AdminAuditDelivery(source, sink);
  }

  @Bean
  AuditPump adminAuditPump(AdminAuditDelivery delivery) {
    return new AuditPump(delivery);
  }

  static final class AuditPump {
    private final AdminAuditDelivery delivery;

    AuditPump(AdminAuditDelivery delivery) {
      this.delivery = delivery;
    }

    @Scheduled(fixedDelay = 5000)
    public void deliver() {
      try {
        delivery.deliverPending(100);
      } catch (RuntimeException e) {
        System.getLogger(AuditPump.class.getName())
            .log(
                System.Logger.Level.WARNING,
                "Admin audit dependency unavailable; durable intents retained");
      }
    }
  }
}
