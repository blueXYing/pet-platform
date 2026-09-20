package com.petplatform.task.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcAsyncTaskSubmitterTest {
  @Test
  void enqueueRequiresBusinessTransactionAndRollsBackWithItsIntent() throws Exception {
    try (var db = new MySqlTestDatabase()) {
      var ids = new AtomicLong(6000);
      var submitter = new JdbcAsyncTaskSubmitter(db.dataSource(), ids::incrementAndGet);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
      assertThrows(IllegalStateException.class, () -> enqueue(submitter, "atomic:1", "1"));
      assertThrows(
          IllegalStateException.class,
          () ->
              tx.execute(
                  status -> {
                    enqueue(submitter, "atomic:1", "1");
                    throw new IllegalStateException("Simulated business intent rollback");
                  }));
      assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
      long id = tx.execute(status -> enqueue(submitter, "atomic:1", "1"));
      long replay = tx.execute(status -> enqueue(submitter, "atomic:1", "1"));
      assertEquals(id, replay);
      assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
      assertThrows(
          IllegalArgumentException.class,
          () -> tx.execute(status -> enqueue(submitter, "atomic:1", "2")));
      assertEquals(
          "READY",
          db.jdbc().queryForObject("SELECT status FROM async_task WHERE id=?", String.class, id));
      var lease =
          db.repository(ids::incrementAndGet)
              .claim("submitter-test", java.time.Duration.ofSeconds(30));
      assertTrue(lease.isPresent());
      assertEquals("PRIVATE_ASSET_RECONCILE", lease.orElseThrow().taskType());
      assertEquals(id, lease.orElseThrow().taskId());
    }
  }

  @Test
  void transactionForDifferentDataSourceCannotAccidentallyAutocommit() throws Exception {
    try (var first = new MySqlTestDatabase();
        var second = new MySqlTestDatabase()) {
      var ids = new AtomicLong(7000);
      var submitter = new JdbcAsyncTaskSubmitter(second.dataSource(), ids::incrementAndGet);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(first.dataSource()));
      assertThrows(
          IllegalStateException.class,
          () -> tx.execute(status -> enqueue(submitter, "other-ds", "1")));
      assertEquals(
          0, second.jdbc().queryForObject("SELECT COUNT(*) FROM async_task", Integer.class));
    }
  }

  private static long enqueue(JdbcAsyncTaskSubmitter submitter, String key, String asset) {
    return submitter.enqueue(
        key,
        "thirdparty",
        "PRIVATE_ASSET_RECONCILE",
        "PRIVATE_ASSET",
        1,
        0L,
        "{\"assetId\":\"" + asset + "\"}",
        10,
        "FAST_INTERNAL");
  }
}
