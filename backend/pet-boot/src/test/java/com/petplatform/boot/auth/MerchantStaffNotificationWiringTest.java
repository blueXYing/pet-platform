package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.notification.biz.event.MerchantStaffInvitationConsumer;
import com.petplatform.notification.biz.event.MerchantStaffMemberConsumer;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract-54 NTF slice wiring acceptance: with {@code pet.merchant.staff.notifications-enabled=
 * true} the real Spring context registers both staff consumers behind the outbox dispatcher, an
 * invitation lifecycle event appended through the real TransactionalOutboxPublisher inside a
 * caller transaction is dispatched into the authoritative notification rows (owner + confirmed
 * employee for CONFIRMED), and with the switch off (default) neither consumer bean exists. The
 * C-end inbox HTTP visibility of these rows is covered by the shared NTF-001 inbox surface
 * (ServiceReviewedNotificationWiringHttpTest pattern); the write-side command-to-outbox chain is
 * covered by MerchantStaffMemberEventMySqlTest.
 */
class MerchantStaffNotificationWiringTest {
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private ConfigurableApplicationContext offContext;

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (context != null) context.close();
    } finally {
      try {
        if (offContext != null) offContext.close();
      } finally {
        if (db != null) db.close();
      }
    }
  }

  private ConfigurableApplicationContext boot(Map<String, Object> props) {
    return new SpringApplicationBuilder(PetPlatformApplication.class)
        .initializers(
            ctx -> {
              var beans = (GenericApplicationContext) ctx;
              beans.registerBean(
                  "staffNtfDataSource", javax.sql.DataSource.class, () -> db.source);
              beans.registerBean("staffNtfIds", SnowflakeIdGenerator.class, () -> db.ids);
              beans.registerBean(
                  "staffNtfWechat", WechatSessionProvider.class,
                  CAuthHttpTest.FixedWechatProvider::new);
            })
        .run(
            props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new));
  }

  private static Map<String, Object> baseProps() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", 0);
    props.put("spring.flyway.enabled", false);
    props.put("spring.main.banner-mode", "off");
    props.put("spring.jmx.enabled", false);
    props.put("pet.auth.c.enabled", true);
    props.put("pet.auth.c.redis-host", dbRedisHost());
    props.put("pet.auth.c.redis-port", dbRedisPort());
    return props;
  }

  private static String dbRedisHost() {
    return System.getenv().getOrDefault("AUTH_REDIS_HOST", "127.0.0.1");
  }

  private static String dbRedisPort() {
    return System.getenv().getOrDefault("AUTH_REDIS_PORT", "16379");
  }

  private Map<String, Object> invitationPayload(String changeType) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("invitationId", "9001");
    payload.put("merchantId", "9101");
    payload.put("storeId", "9201");
    payload.put("ownerUserId", "9101");
    payload.put("memberName", "布线成员");
    payload.put("phoneMasked", "139****9999");
    payload.put("changeType", changeType);
    if ("CONFIRMED".equals(changeType)) {
      payload.put("confirmedUserId", "9202");
      payload.put("memberId", "9301");
    } else {
      payload.put("confirmedUserId", null);
      payload.put("memberId", null);
    }
    payload.put("occurredAt", "2026-10-06T09:30:00.000Z");
    return payload;
  }

  @Test
  void staffSwitchRegistersBothConsumersAndDispatchesConfirmToBothReceivers() throws Exception {
    Map<String, Object> props = baseProps();
    props.put("pet.outbox.enabled", true);
    props.put("pet.merchant.staff.notifications-enabled", true);
    context = boot(props);
    MerchantStaffInvitationConsumer invitation = context.getBean(MerchantStaffInvitationConsumer.class);
    assertEquals("notification.merchant-staff-invitation.v1", invitation.consumerName());
    assertTrue(invitation.eventTypes().contains("MerchantStaffInvitationLifecycleEvent.v1"));
    MerchantStaffMemberConsumer member = context.getBean(MerchantStaffMemberConsumer.class);
    assertEquals("notification.merchant-staff-member.v1", member.consumerName());
    assertTrue(member.eventTypes().contains("MerchantStaffMemberLifecycleEvent.v1"));

    TransactionalOutboxPublisher publisher = context.getBean(TransactionalOutboxPublisher.class);
    TransactionTemplate transaction = new TransactionTemplate(
        new DataSourceTransactionManager(db.source));
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    transaction.executeWithoutResult(
        status ->
            publisher.publish(
                new IntegrationEvent<>(
                    null,
                    "MerchantStaffInvitationLifecycleEvent.v1",
                    1,
                    occurredAt,
                    "MERCHANT_MEMBER_INVITATION",
                    "9001",
                    "staff-ntf-wiring-trace",
                    invitationPayload("CONFIRMED"))));
    for (long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos(); ; ) {
      Integer owners =
          db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM notification WHERE receiver_id=9101"
                  + " AND message_type='MER_STAFF_INVITATION' AND biz_id=9001",
              Integer.class);
      Integer employees =
          db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM notification WHERE receiver_id=9202"
                  + " AND title='员工邀请确认成功'",
              Integer.class);
      String outboxStatus =
          db.jdbc.queryForObject(
              "SELECT status FROM integration_event_outbox WHERE aggregate_id=?",
              String.class, 9001L);
      if (owners != null && owners == 1 && employees != null && employees == 1
          && "PUBLISHED".equals(outboxStatus)) {
        break;
      }
      if ((owners != null && owners > 1) || (employees != null && employees > 1)) {
        fail("duplicate staff invitation notifications");
      }
      if (System.nanoTime() >= deadline) {
        fail("staff invitation dispatch did not finish: owners=" + owners
            + ", employees=" + employees + ", outboxStatus=" + outboxStatus);
      }
      Thread.sleep(200);
    }
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM integration_event_consume_log WHERE"
                + " consumer_name='notification.merchant-staff-invitation.v1'",
            Integer.class));

    // Default-off structural negative: same outbox assembly without the staff switch has none of
    // the two consumer beans (mirrors the ServiceWriteHttpTest consumer-absence precedent).
    Map<String, Object> offProps = baseProps();
    offProps.put("pet.outbox.enabled", true);
    offContext = boot(offProps);
    assertEquals(
        0,
        offContext.getBeanNamesForType(MerchantStaffInvitationConsumer.class).length);
    assertEquals(0, offContext.getBeanNamesForType(MerchantStaffMemberConsumer.class).length);
  }
}
