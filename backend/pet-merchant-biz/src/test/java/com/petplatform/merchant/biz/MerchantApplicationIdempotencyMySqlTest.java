package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.CreateMerchantApplicationCommand;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.DraftRevisionInput;
import com.petplatform.merchant.biz.apiimpl.MerchantApplicationApiImpl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** The database commits for real; only the acknowledgement is lost. No fake success binding. */
class MerchantApplicationIdempotencyMySqlTest {
  private final AtomicLong ids = new AtomicLong(8300000000000000L);

  @Test
  void lostAdmissionAcknowledgementRecoversSameBindingAndDurableResult() throws Exception {
    assertRecovered(Set.of(1));
  }

  @Test
  void lostExecutionAcknowledgementReplaysWithoutCreatingAnotherRevision() throws Exception {
    assertRecovered(Set.of(2));
  }

  @Test
  void repeatedUnknownAdmissionRemainsRetryableUnderTheOriginalRequestId() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      var command = create();
      var unstable =
          new MerchantApplicationApiImpl(
              loseAcknowledgements(db.dataSource(), Set.of(1, 2)), ids::incrementAndGet);
      ApiException error = assertThrows(ApiException.class, () -> unstable.createDraft(command));
      assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, error.code());
      assertEquals(
          0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_application", Integer.class));
      var recovered =
          new MerchantApplicationApiImpl(db.dataSource(), ids::incrementAndGet)
              .createDraft(command);
      assertEquals("DRAFT", recovered.status());
      assertEquals(
          1, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_application", Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_application_revision", Integer.class));
    }
  }

  @Test
  void omittedAndExplicitNullDraftFieldsShareOneCanonicalIntent() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      var api = new MerchantApplicationApiImpl(db.dataSource(), ids::incrementAndGet);
      var command = create();
      var first = api.createDraft(command);
      var explicit =
          new DraftRevisionInput(
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              java.util.List.of(),
              null,
              null,
              null,
              null);
      assertEquals(
          first,
          api.createDraft(new CreateMerchantApplicationCommand(explicit, command.context())));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_application_revision", Integer.class));
    }
  }

  private void assertRecovered(Set<Integer> failedCommits) throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      var command = create();
      var unstable =
          new MerchantApplicationApiImpl(
              loseAcknowledgements(db.dataSource(), failedCommits), ids::incrementAndGet);
      var created = unstable.createDraft(command);
      // A new instance cannot rely on in-memory request state for the replay.
      var replay =
          new MerchantApplicationApiImpl(db.dataSource(), ids::incrementAndGet)
              .createDraft(command);
      assertEquals(created, replay);
      assertNotNull(replay.currentRevision());
      assertEquals("DRAFT", replay.status());
      assertEquals(
          1, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_application", Integer.class));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_application_revision", Integer.class));
    }
  }

  private CreateMerchantApplicationCommand create() {
    return new CreateMerchantApplicationCommand(
        null,
        new CommandContext(
            UUID.randomUUID().toString(),
            "application-ack-test",
            OperatorType.USER,
            "770001",
            "C_MINIAPP"));
  }

  private static DataSource loseAcknowledgements(DataSource delegate, Set<Integer> failedCommits) {
    AtomicInteger commits = new AtomicInteger();
    return new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
      }

      @Override
      public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
      }

      private Connection wrap(Connection connection) {
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  try {
                    Object value = method.invoke(connection, args);
                    if (method.getName().equals("commit")
                        && failedCommits.contains(commits.incrementAndGet()))
                      throw new SQLException("simulated lost commit acknowledgement", "08006");
                    return value;
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }
}
