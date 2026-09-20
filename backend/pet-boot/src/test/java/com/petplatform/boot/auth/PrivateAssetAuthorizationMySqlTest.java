package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.*;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.boot.config.PrivateAssetReadAuthorizationAdapter;
import com.petplatform.common.*;
import com.petplatform.merchant.biz.apiimpl.MerchantApplicationApiImpl;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL proof that private reads join current MER locks and current ADMIN authorization. */
class PrivateAssetAuthorizationMySqlTest {
  private static final char[] PASSWORD = "Example_ONLY_92!".toCharArray();
  private CAuthHttpTest.HttpFixture db;
  private RedisAdminGrantCache cache;
  private String authCachePrefix;
  private PrivateAssetReadAuthorizationAdapter adapter;
  private TransactionTemplate transaction;
  private long operator;
  private String sessionId;
  private long generation;

  @BeforeEach
  void open() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    Path root = CAuthHttpTest.HttpFixture.root();
    try (var connection = db.source.getConnection()) {
      for (String file :
          List.of("26-Admin-Auth-Schema-v0.1.sql", "29-Merchant-Application-Schema-v0.1.sql")) {
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(
                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                StandardCharsets.UTF_8));
      }
    }
    authCachePrefix = "auth001_private_" + db.name + ":";
    cache = new RedisAdminGrantCache(db.redisHost, db.redisPort, null, null, authCachePrefix);
    AdminAuthService auth =
        new AdminAuthService(
            db.source,
            db.ids,
            Clock.systemUTC(),
            new AdminPasswordHasher(),
            AdminSecretCodec.fixed("qa-private", key(3), key(7)),
            cache);
    operator =
        auth.bootstrap(
            "qa-private-reviewer",
            "QA Private Reviewer",
            PASSWORD.clone(),
            "Private asset authorization integration");
    AdminSecretResult attempt = auth.createAttempt(UUID.randomUUID().toString(), "127.0.0.1");
    long attemptId = Long.parseLong(attempt.data().get("attemptId").toString());
    AdminSecretResult login =
        auth.login(
            UUID.randomUUID().toString(),
            attemptId,
            attempt.data().get("attemptToken").toString(),
            attempt.bindingCookie(),
            "qa-private-reviewer",
            PASSWORD.clone(),
            null);
    var session = auth.resolveSession(login.data().get("accessToken").toString());
    sessionId = session.principal().sessionId();
    generation = session.principal().sessionGeneration();
    seedApplication();
    adapter =
        new PrivateAssetReadAuthorizationAdapter(
            new MerchantApplicationApiImpl(db.source, db.ids),
            new AdminAuthorizationService(db.source));
    transaction = new TransactionTemplate(new DataSourceTransactionManager(db.source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @AfterEach
  void close() {
    try {
      if (cache != null) cache.close();
      if (db != null && authCachePrefix != null) {
        var client =
            io.lettuce.core.RedisClient.create("redis://" + db.redisHost + ":" + db.redisPort);
        try (var connection = client.connect()) {
          for (String key : connection.sync().keys(authCachePrefix + "*"))
            connection.sync().del(key);
        } finally {
          client.shutdown();
        }
      }
    } finally {
      if (db != null) db.close();
    }
  }

  @Test
  void crossScopeAndSessionGenerationRevocationAreCurrentDenials() {
    configureReviewerCity("310100");
    assertEquals(CommonApiCodes.FORBIDDEN, failure().code());

    configureReviewerCity("110100");
    assertEquals("501", authorize().ownerUserId());
    db.jdbc.update(
        "UPDATE admin_account SET session_generation=session_generation+1 WHERE id=?", operator);
    assertEquals(CommonApiCodes.FORBIDDEN, failure().code());
  }

  @Test
  void revokedSessionIsRejectedByTheCurrentLockedAuthorizationRead() {
    db.jdbc.update(
        "UPDATE admin_web_session SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3) WHERE id=?",
        Long.parseLong(sessionId));
    assertEquals(CommonApiCodes.FORBIDDEN, failure().code());
  }

  @Test
  void releaseOrNewSubmissionInvalidatesCapturedClaimAndScopeVersion() {
    ReadAuthorizationProof issued = authorize();
    assertEquals("7:2", issued.scopeVersion());
    db.jdbc.update(
        "UPDATE merchant_application_review_task SET"
            + " status='AVAILABLE',claimed_by_operator_id=NULL, claimed_at=NULL,version=version+1"
            + " WHERE id=601");
    assertEquals(CommonApiCodes.FORBIDDEN, failure().code());

    db.jdbc.update(
        "UPDATE merchant_application_review_task SET status='CLAIMED',claimed_by_operator_id=?,"
            + " claimed_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=601",
        operator);
    db.jdbc.update(
        "INSERT INTO merchant_application_revision"
            + "(id,application_id,revision_no,merchant_name,merchant_type_code,city_code,created_by_user_id,created_at)"
            + " VALUES(302,101,2,'QA Merchant','PET_LIFE_STORE','110100',501,UTC_TIMESTAMP(3))");
    db.jdbc.update(
        "INSERT INTO merchant_application_revision_material"
            + "(application_id,revision_id,material_id,material_type,position)"
            + " VALUES(101,302,401,'ID_CARD_FRONT',1)");
    db.jdbc.update(
        "INSERT INTO merchant_application_review_task"
            + "(id,application_id,submitted_revision_id,submission_no,status,claimed_by_operator_id,claimed_at,version,updated_at)"
            + " VALUES(602,101,302,2,'CLAIMED',?,UTC_TIMESTAMP(3),1,UTC_TIMESTAMP(3))",
        operator);
    db.jdbc.update(
        "UPDATE merchant_application SET current_revision_id=302,submitted_revision_id=302,"
            + " current_review_task_id=602,version=version+1 WHERE id=101");
    assertEquals(CommonApiCodes.CONFLICT, failure().code());
  }

  @Test
  void authorizationRowsStayLockedUntilCallerTransactionCommitsThenRemovalIsObserved() {
    configureReviewerCity("110100");
    CompletableFuture<Void>[] mutation = new CompletableFuture[1];
    transaction.executeWithoutResult(
        ignored -> {
          ReadAuthorizationProof proof = adapter.authorize(request());
          assertFalse(proof.authzVersion().isBlank());
          mutation[0] =
              CompletableFuture.runAsync(
                  () -> {
                    long reviewer = reviewerRole();
                    db.jdbc.update(
                        "DELETE FROM admin_role_action WHERE role_id=? AND"
                            + " action_code='merchant.identity.reveal'",
                        reviewer);
                    db.jdbc.update(
                        "UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
                  });
          try {
            Thread.sleep(200);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(interrupted);
          }
          assertFalse(
              mutation[0].isDone(), "authorization mutation must wait for caller transaction");
        });
    mutation[0].join();
    assertEquals(CommonApiCodes.FORBIDDEN, failure().code());
  }

  @Test
  void claimantMutationWaitsForAuthorizationTransactionAndThenInvalidatesConsumption() {
    CompletableFuture<Void>[] release = new CompletableFuture[1];
    transaction.executeWithoutResult(
        ignored -> {
          assertEquals("401", adapter.authorize(request()).materialId());
          release[0] =
              CompletableFuture.runAsync(
                  () ->
                      db.jdbc.update(
                          "UPDATE merchant_application_review_task SET status='AVAILABLE',"
                              + " claimed_by_operator_id=NULL,claimed_at=NULL,version=version+1"
                              + " WHERE id=601"));
          try {
            Thread.sleep(200);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(interrupted);
          }
          assertFalse(release[0].isDone(), "claim release must wait for private read transaction");
        });
    release[0].join();
    assertEquals(CommonApiCodes.FORBIDDEN, failure().code());
  }

  private ReadAuthorizationProof authorize() {
    return transaction.execute(ignored -> adapter.authorize(request()));
  }

  private ApiException failure() {
    return assertThrows(ApiException.class, this::authorize);
  }

  private ReadAuthorizationRequest request() {
    return new ReadAuthorizationRequest(
        ReadAuthorizationPhase.CONSUME,
        "101",
        "301",
        "801",
        Long.toString(operator),
        sessionId,
        generation,
        "APPLICATION_REVIEW");
  }

  private void seedApplication() {
    db.jdbc.update(
        "INSERT INTO merchant_application"
            + "(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at)"
            + " VALUES(101,501,201,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
    db.jdbc.update(
        "INSERT INTO merchant_application_revision"
            + "(id,application_id,revision_no,merchant_name,merchant_type_code,city_code,created_by_user_id,created_at)"
            + " VALUES(301,101,1,'QA Merchant','PET_LIFE_STORE','110100',501,UTC_TIMESTAMP(3))");
    db.jdbc.update(
        "INSERT INTO merchant_application_material"
            + "(id,application_id,material_type,private_asset_id,sha256,media_type,bytes,uploaded_by_user_id,created_at)"
            + " VALUES(401,101,'ID_CARD_FRONT',801,?,'image/png',1024,501,UTC_TIMESTAMP(3))",
        "a".repeat(64));
    db.jdbc.update(
        "INSERT INTO merchant_application_revision_material"
            + "(application_id,revision_id,material_id,material_type,position)"
            + " VALUES(101,301,401,'ID_CARD_FRONT',1)");
    db.jdbc.update(
        "INSERT INTO merchant_application_review_task"
            + "(id,application_id,submitted_revision_id,submission_no,status,claimed_by_operator_id,claimed_at,version,updated_at)"
            + " VALUES(601,101,301,1,'CLAIMED',?,UTC_TIMESTAMP(3),2,UTC_TIMESTAMP(3))",
        operator);
    db.jdbc.update(
        "UPDATE merchant_application SET application_no='SQ20260920ABCDEFGH',status='REVIEWING',"
            + " current_revision_id=301,submitted_revision_id=301,current_review_task_id=601,"
            + " subject_verification_status='SUBJECT_VERIFICATION_PENDING',version=7,"
            + " submitted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=101");
  }

  private void configureReviewerCity(String city) {
    long reviewer = reviewerRole();
    db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT IGNORE INTO admin_account_role(account_id,role_id,granted_by,granted_at)"
            + " VALUES(?,?,?,UTC_TIMESTAMP(3))",
        operator,
        reviewer,
        operator);
    db.jdbc.update("DELETE FROM admin_role_action WHERE role_id=?", reviewer);
    db.jdbc.update(
        "INSERT INTO admin_role_action(role_id,action_code,created_at) VALUES"
            + "(?,'merchant.application.decide',UTC_TIMESTAMP(3)),"
            + "(?,'merchant.identity.reveal',UTC_TIMESTAMP(3))",
        reviewer,
        reviewer);
    db.jdbc.update(
        "UPDATE admin_account_scope SET mode='CITY',version=version+1 WHERE account_id=?",
        operator);
    db.jdbc.update("DELETE FROM admin_scope_city WHERE account_id=?", operator);
    db.jdbc.update(
        "INSERT INTO admin_scope_city(account_id,city_code) VALUES(?,?)", operator, city);
    db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
  }

  private long reviewerRole() {
    return db.jdbc.queryForObject(
        "SELECT id FROM admin_role WHERE role_code='REVIEWER'", Long.class);
  }

  private static byte[] key(int fill) {
    byte[] value = new byte[32];
    Arrays.fill(value, (byte) fill);
    return value;
  }
}
