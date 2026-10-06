package com.petplatform.notification.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.command.NotificationPreferenceApi.UpdatePreferenceCommand;
import com.petplatform.notification.api.dto.NotificationTypes.PreferenceView;
import com.petplatform.notification.biz.apiimpl.NotificationPreferenceApiImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * SSOT §16.4 preference facts over real MySQL (schema 06 §11 + 14号 binding storage): unread
 * defaults, owner isolation, supplement-23 replay/conflict semantics and the version counter.
 */
class NotificationPreferenceServiceMySqlTest {
  private static final long USER_A = 4100;
  private static final long USER_B = 4200;
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-06T09:30:00.000Z"), ZoneOffset.UTC);

  private MySqlNotificationTestDatabase db;
  private NotificationPreferenceApiImpl api;

  @BeforeEach
  void start() throws Exception {
    db = new MySqlNotificationTestDatabase();
    try (var connection = db.dataSource().getConnection()) {
      Path root = Path.of("").toAbsolutePath();
      while (root != null && !Files.isDirectory(root.resolve("docs/03-database"))) root = root.getParent();
      ScriptUtils.executeSqlScript(
          connection,
          new org.springframework.core.io.support.EncodedResource(
              new FileSystemResource(root.resolve("docs/03-database/14-Command-Idempotency-Schema-v0.1.sql")),
              StandardCharsets.UTF_8));
    }
    api = new NotificationPreferenceApiImpl(
        db.dataSource(), clock,
        () -> java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
  }

  @AfterEach
  void stop() {
    if (db != null) db.close();
  }

  private static QueryContext query(long userId) {
    return new QueryContext("trace-pref", OperatorType.USER, String.valueOf(userId));
  }

  private static CommandContext command(long userId, String requestId) {
    return new CommandContext(requestId, "trace-pref", OperatorType.USER,
        String.valueOf(userId), "C_MINIAPP");
  }

  private static UpdatePreferenceCommand update(
      long userId, String requestId, boolean interaction, boolean push) {
    return new UpdatePreferenceCommand(interaction, push, command(userId, requestId));
  }

  @Test
  void missingRowReadsAsSchemaDefaultsAndOtherReceiversStayIsolated() {
    PreferenceView defaults = api.getPreference(query(USER_A));
    assertTrue(defaults.interactionEnabled());
    assertTrue(defaults.externalPushEnabled());
    assertEquals("0", defaults.version());
    assertNull(defaults.updatedAt());

    api.updatePreference(update(USER_A, uuid(), false, false));
    assertEquals(0, db.jdbc().queryForObject(
        "SELECT COUNT(*) FROM notification_preference WHERE receiver_id=?", Integer.class, USER_B));
    PreferenceView other = api.getPreference(query(USER_B));
    assertTrue(other.interactionEnabled());
    assertEquals("0", other.version());
  }

  @Test
  void updateReplacesBothSwitchesAndBumpsVersion() {
    api.updatePreference(update(USER_A, uuid(), true, false));
    PreferenceView first = api.getPreference(query(USER_A));
    assertTrue(first.interactionEnabled());
    assertFalse(first.externalPushEnabled());
    assertEquals("0", first.version());
    assertNotNull(first.updatedAt());

    api.updatePreference(update(USER_A, uuid(), false, true));
    PreferenceView second = api.getPreference(query(USER_A));
    assertFalse(second.interactionEnabled());
    assertTrue(second.externalPushEnabled());
    assertEquals("1", second.version());
  }

  @Test
  void sameRequestIdReplaysFirstReceiptAndDifferentParametersConflict() {
    String requestId = uuid();
    PreferenceView first = api.updatePreference(update(USER_A, requestId, false, true));
    PreferenceView replay = api.updatePreference(update(USER_A, requestId, false, true));
    assertEquals(first.version(), replay.version());
    assertEquals(first.updatedAt(), replay.updatedAt());
    assertFalse(replay.interactionEnabled());
    assertTrue(replay.externalPushEnabled());
    // One row, one binding, and the replay did not bump anything.
    assertEquals("0", replay.version());
    assertEquals(1, db.jdbc().queryForObject(
        "SELECT COUNT(*) FROM notification_preference WHERE receiver_id=?", Integer.class, USER_A));

    ApiException conflict =
        assertThrows(ApiException.class,
            () -> api.updatePreference(update(USER_A, requestId, true, true)));
    assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
    // The conflicting attempt did not change the stored state.
    assertTrue(db.jdbc().queryForObject(
        "SELECT interaction_enabled FROM notification_preference WHERE receiver_id=?",
        Boolean.class, USER_A) == false);
  }

  @Test
  void theSameRequestIdServesADifferentUserIndependently() {
    String shared = uuid();
    PreferenceView a = api.updatePreference(update(USER_A, shared, false, false));
    PreferenceView b = api.updatePreference(update(USER_B, shared, true, true));
    assertFalse(a.interactionEnabled());
    assertTrue(b.interactionEnabled());
    // Each actor owns its binding; two bindings exist under the shared requestId.
    assertEquals(2, db.jdbc().queryForObject(
        "SELECT COUNT(*) FROM command_idempotency WHERE request_key LIKE ?",
        Integer.class, "%|" + shared.length() + ":" + shared + "|%"));
  }

  @Test
  void unauthenticatedOrNonUserContextsAreRejectedBeforeAnyWrite() {
    ApiException anonymous =
        assertThrows(ApiException.class, () -> api.getPreference(new QueryContext("t", null, null)));
    assertEquals(CommonApiCodes.UNAUTHORIZED, anonymous.code());
    ApiException blankRequestId =
        assertThrows(ApiException.class, () -> api.updatePreference(update(USER_A, " ", false, false)));
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, blankRequestId.code());
    assertEquals(0, db.jdbc().queryForObject(
        "SELECT COUNT(*) FROM command_idempotency", Integer.class));
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }
}
