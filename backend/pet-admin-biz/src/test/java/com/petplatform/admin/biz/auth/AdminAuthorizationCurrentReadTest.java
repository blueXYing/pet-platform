package com.petplatform.admin.biz.auth;

import static com.petplatform.admin.biz.auth.AuthTestDatabase.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionCheckQuery.CheckPhase;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.biz.application.AdminAuthorizationService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AdminAuthorizationCurrentReadTest {
  AuthTestDatabase db;
  AdminAuthorizationService authorization;
  long operator;
  String sessionId;
  long generation;

  @BeforeEach
  void open() throws Exception {
    db = new AuthTestDatabase();
    var auth = db.service();
    operator = db.bootstrap(auth);
    var view = auth.resolveSession(db.loginToken(auth));
    sessionId = view.principal().sessionId();
    generation = view.principal().sessionGeneration();
    authorization = db.authorization();
  }

  @AfterEach
  void close() {
    if (db != null) db.close();
  }

  @Test
  void revocationExpiryAndGenerationChangesAreCurrentDenials() {
    assertAllowed(query("merchant.application.decide", CheckPhase.EXECUTE));

    db.jdbc.update(
        "UPDATE admin_web_session SET status='REVOKED',revoked_at=NOW(3) WHERE id=?", sessionId);
    assertDenied(query("merchant.application.decide", CheckPhase.EXECUTE), "SESSION_REVOKED");

    db.jdbc.update(
        "UPDATE admin_web_session SET status='ACTIVE',revoked_at=NULL,idle_expires_at='2000-01-01"
            + " 00:00:00.000' WHERE id=?",
        sessionId);
    assertDenied(query("merchant.application.decide", CheckPhase.EXECUTE), "SESSION_EXPIRED");

    db.jdbc.update(
        "UPDATE admin_web_session SET idle_expires_at=DATE_ADD(NOW(3),INTERVAL 30 MINUTE) WHERE"
            + " id=?",
        sessionId);
    db.jdbc.update(
        "UPDATE admin_account SET session_generation=session_generation+1 WHERE id=?", operator);
    assertDenied(
        query("merchant.application.decide", CheckPhase.READ_RESULT), "SESSION_GENERATION_CHANGED");
  }

  @Test
  void removedActionAndChangedCityScopeAreObservedWithoutEntrySnapshotTrust() {
    long reviewer =
        db.jdbc.queryForObject("SELECT id FROM admin_role WHERE role_code='REVIEWER'", Long.class);
    db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
            + " VALUES(?,?,?,NOW(3))",
        operator,
        reviewer,
        operator);
    db.jdbc.update(
        "INSERT INTO admin_role_action(role_id,action_code,created_at)"
            + " VALUES(?,'merchant.application.decide',NOW(3))",
        reviewer);
    db.jdbc.update(
        "UPDATE admin_account_scope SET mode='CITY',version=version+1 WHERE account_id=?",
        operator);
    db.jdbc.update(
        "INSERT INTO admin_scope_city(account_id,city_code) VALUES(?,'310100')", operator);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");

    assertDenied(query("merchant.application.decide", CheckPhase.EXECUTE), "RESOURCE_SCOPE_DENIED");
    db.jdbc.update("DELETE FROM admin_scope_city WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT INTO admin_scope_city(account_id,city_code) VALUES(?,'110100')", operator);
    db.jdbc.update("UPDATE admin_account_scope SET version=version+1 WHERE account_id=?", operator);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
    assertAllowed(query("merchant.application.decide", CheckPhase.EXECUTE));

    db.jdbc.update(
        "DELETE FROM admin_role_action WHERE role_id=? AND"
            + " action_code='merchant.application.decide'",
        reviewer);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
    assertDenied(
        query("merchant.application.decide", CheckPhase.READ_RESULT), "ACTION_NOT_GRANTED");
  }

  @Test
  void operatorMismatchAndServerBoundMerchantScopeDeny() {
    assertDenied(
        new AdminActionCheckQuery(
            sessionId,
            generation,
            Long.toString(operator + 1),
            "merchant.application.read",
            resource(),
            "MERCHANT_APPLICATION_REVIEW",
            CheckPhase.READ_RESULT),
        "OPERATOR_MISMATCH");

    long reviewer =
        db.jdbc.queryForObject("SELECT id FROM admin_role WHERE role_code='REVIEWER'", Long.class);
    db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
            + " VALUES(?,?,?,NOW(3))",
        operator,
        reviewer,
        operator);
    db.jdbc.update(
        "INSERT INTO admin_role_action(role_id,action_code,created_at)"
            + " VALUES(?,'merchant.application.read',NOW(3))",
        reviewer);
    db.jdbc.update(
        "UPDATE admin_account_scope SET mode='MERCHANT',version=version+1 WHERE account_id=?",
        operator);
    db.jdbc.update(
        "INSERT INTO admin_scope_merchant(account_id,merchant_id) VALUES(?,9002)", operator);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
    assertDenied(
        query("merchant.application.read", CheckPhase.READ_RESULT), "RESOURCE_SCOPE_DENIED");
  }

  @Test
  void outerRepeatableReadSnapshotCannotHideCommittedActionRemoval() {
    long reviewer = configureReviewerCity("110100", "merchant.application.decide");
    var transaction = repeatableRead();

    var decision =
        transaction.execute(
            ignored -> {
              assertEquals(
                  "merchant.application.decide",
                  db.jdbc.queryForObject(
                      "SELECT ra.action_code FROM admin_role_action ra JOIN admin_account_role ar"
                          + " ON ar.role_id=ra.role_id WHERE ar.account_id=?",
                      String.class,
                      operator));
              CompletableFuture.runAsync(
                      () -> {
                        db.jdbc.update(
                            "DELETE FROM admin_role_action WHERE role_id=? AND"
                                + " action_code='merchant.application.decide'",
                            reviewer);
                        db.jdbc.update(
                            "UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
                      })
                  .join();
              return authorization.check(query("merchant.application.decide", CheckPhase.EXECUTE));
            });

    assertNotNull(decision);
    assertFalse(decision.allowed());
    assertEquals("ACTION_NOT_GRANTED", decision.reasonCode());
  }

  @Test
  void outerRepeatableReadSnapshotCannotHideCommittedScopeRemoval() {
    configureReviewerCity("110100", "merchant.application.decide");
    var transaction = repeatableRead();

    var decision =
        transaction.execute(
            ignored -> {
              assertEquals(
                  "110100",
                  db.jdbc.queryForObject(
                      "SELECT city_code FROM admin_scope_city WHERE account_id=?",
                      String.class,
                      operator));
              CompletableFuture.runAsync(
                      () -> {
                        db.jdbc.update("DELETE FROM admin_scope_city WHERE account_id=?", operator);
                        db.jdbc.update(
                            "INSERT INTO admin_scope_city(account_id,city_code) VALUES(?,'310100')",
                            operator);
                        db.jdbc.update(
                            "UPDATE admin_account_scope SET version=version+1 WHERE account_id=?",
                            operator);
                        db.jdbc.update(
                            "UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
                      })
                  .join();
              return authorization.check(query("merchant.application.decide", CheckPhase.EXECUTE));
            });

    assertNotNull(decision);
    assertFalse(decision.allowed());
    assertEquals("RESOURCE_SCOPE_DENIED", decision.reasonCode());
  }

  @Test
  void emptyCollectionGateStillRequiresCurrentSessionAndReadAction() {
    assertAllowed(authorization.checkCollection(collectionQuery()));

    long reviewer =
        db.jdbc.queryForObject("SELECT id FROM admin_role WHERE role_code='REVIEWER'", Long.class);
    db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
            + " VALUES(?,?,?,NOW(3))",
        operator,
        reviewer,
        operator);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
    assertDenied(authorization.checkCollection(collectionQuery()), "ACTION_NOT_GRANTED");

    db.jdbc.update(
        "UPDATE admin_web_session SET status='REVOKED',revoked_at=NOW(3) WHERE id=?", sessionId);
    assertDenied(authorization.checkCollection(collectionQuery()), "SESSION_REVOKED");
  }

  @Test
  void cityScopedReadPassesCollectionGateButStillFiltersEachResource() {
    configureReviewerCity("310100", "merchant.application.read");
    assertAllowed(authorization.checkCollection(collectionQuery()));
    assertDenied(
        query("merchant.application.read", CheckPhase.READ_RESULT), "RESOURCE_SCOPE_DENIED");
  }

  private AdminActionCheckQuery query(String action, CheckPhase phase) {
    return new AdminActionCheckQuery(
        sessionId,
        generation,
        Long.toString(operator),
        action,
        resource(),
        "MERCHANT_APPLICATION_REVIEW",
        phase);
  }

  private AdminResourceScope resource() {
    return new AdminResourceScope("MERCHANT_APPLICATION", "7001", "9001", "110100", "12");
  }

  private AdminCollectionActionCheckQuery collectionQuery() {
    return new AdminCollectionActionCheckQuery(
        sessionId,
        generation,
        Long.toString(operator),
        "merchant.application.read",
        "MERCHANT_APPLICATION_LIST",
        CheckPhase.READ_RESULT);
  }

  private long configureReviewerCity(String cityCode, String action) {
    long reviewer =
        db.jdbc.queryForObject("SELECT id FROM admin_role WHERE role_code='REVIEWER'", Long.class);
    db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
            + " VALUES(?,?,?,NOW(3))",
        operator,
        reviewer,
        operator);
    db.jdbc.update(
        "INSERT INTO admin_role_action(role_id,action_code,created_at) VALUES(?,?,NOW(3))",
        reviewer,
        action);
    db.jdbc.update(
        "UPDATE admin_account_scope SET mode='CITY',version=version+1 WHERE account_id=?",
        operator);
    db.jdbc.update(
        "INSERT INTO admin_scope_city(account_id,city_code) VALUES(?,?)", operator, cityCode);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
    return reviewer;
  }

  private TransactionTemplate repeatableRead() {
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return transaction;
  }

  private void assertAllowed(AdminActionCheckQuery query) {
    assertAllowed(authorization.check(query));
  }

  private void assertAllowed(com.petplatform.admin.api.dto.AdminActionDecision decision) {
    assertTrue(decision.allowed());
    assertEquals("ALLOWED", decision.reasonCode());
    assertNotNull(decision.checkedAt());
    assertFalse(decision.authzVersion().isBlank());
  }

  private void assertDenied(AdminActionCheckQuery query, String reason) {
    assertDenied(authorization.check(query), reason);
  }

  private void assertDenied(
      com.petplatform.admin.api.dto.AdminActionDecision decision, String reason) {
    assertFalse(decision.allowed());
    assertEquals(reason, decision.reasonCode());
  }
}
