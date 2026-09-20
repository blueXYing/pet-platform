package com.petplatform.thirdparty.biz;

import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.task.core.*;
import com.petplatform.thirdparty.api.PrivateAssetApiCodes;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetReconcileTaskHandler;
import com.petplatform.thirdparty.biz.application.port.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PrivateAssetCoreMySqlTest {
  private static final String PURPOSE = PrivateAssetApiImpl.MERCHANT_APPLICATION_MATERIAL;
  private static final byte[] SOURCE = "bounded-image-source".getBytes(StandardCharsets.UTF_8);

  @Test
  void uploadIntentAndSql13TaskPrecedePutAndLostAckReusesId() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var store = new MemoryStore();
      store.loseFirstPutAck = true;
      var api = fixture(db, store, Clock.systemUTC());
      var first = upload("00000000-0000-0000-0000-000000000001");
      ApiException unavailable = assertThrows(ApiException.class, () -> api.upload(first));
      assertEquals(PrivateAssetApiCodes.ASSET_NOT_READY, unavailable.code());
      Long assetId =
          db.jdbc().queryForObject("SELECT asset_id FROM private_asset_upload_request", Long.class);
      assertNotNull(assetId);
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM async_task WHERE task_key=? AND status='READY'",
                  Integer.class,
                  "PRIVATE_ASSET_RECONCILE:" + assetId));

      AtomicLong workerIds = new AtomicLong(50_000);
      var handler = new PrivateAssetReconcileTaskHandler(api);
      try (var worker =
          new AsyncTaskWorker(
              new JdbcAsyncTaskRepository(db.dataSource(), workerIds::incrementAndGet),
              "private-test-worker",
              Clock.systemUTC(),
              TaskWorkerSettings.defaults(),
              new TaskRetryDelays(Map.of("FAST_INTERNAL", List.of(Duration.ofMillis(10)))),
              List.of(handler.registration(new ObjectMapper())))) {
        assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
      }
      assertEquals(
          "READY",
          db.jdbc()
              .queryForObject(
                  "SELECT status FROM private_asset WHERE id=?", String.class, assetId));
      assertNotNull(
          db.jdbc()
              .queryForObject(
                  "SELECT source_object_version_ref FROM private_asset WHERE id=?",
                  String.class,
                  assetId));

      UploadPrivateAssetResult replay = api.upload(upload(first.context().requestId()));
      assertFalse(replay.created());
      assertEquals(Long.toString(assetId), replay.assetId());
      assertEquals(PrivateAssetStatus.READY, replay.status());
      assertTrue(store.putCalls >= 2, "worker recovers with deterministic conditional PUTs");
    }
  }

  @Test
  void grantReplayUsesDatabaseExpiryAndConsumeFailureStaysConsumedWithAudit() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var store = new MemoryStore();
      var api = fixture(db, store, Clock.systemUTC());
      UploadPrivateAssetResult uploaded =
          api.upload(upload("00000000-0000-0000-0000-000000000002"));
      var context =
          context("00000000-0000-0000-0000-000000000003", OperatorType.PLATFORM_OPERATOR, "77");
      var issue =
          new IssuePrivateAssetReadGrantCommand(
              uploaded.assetId(),
              "901",
              "902",
              "MERCHANT_APPLICATION_REVIEW",
              "review evidence",
              "session-1",
              4,
              context);
      IssuedPrivateAssetReadGrant issued = api.issueReadGrant(issue);
      IssuedPrivateAssetReadGrant replay = api.issueReadGrant(issue);
      assertEquals(issued.token(), replay.token());
      ApiException conflict =
          assertThrows(
              ApiException.class,
              () ->
                  api.issueReadGrant(
                      new IssuePrivateAssetReadGrantCommand(
                          uploaded.assetId(),
                          "901",
                          "902",
                          "MERCHANT_APPLICATION_REVIEW",
                          "different reason",
                          "session-1",
                          4,
                          context)));
      assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
      OffsetDateTime databaseExpiry =
          db.jdbc()
              .queryForObject(
                  "SELECT expires_at FROM private_asset_read_grant", LocalDateTime.class)
              .atOffset(ZoneOffset.UTC);
      assertEquals(databaseExpiry, issued.expiresAt());

      store.failGet = true;
      var consume =
          new ConsumePrivateAssetReadGrantCommand(
              issued.token(),
              "session-1",
              4,
              context("consume-1", OperatorType.PLATFORM_OPERATOR, "77"));
      ApiException failed = assertThrows(ApiException.class, () -> api.consumeReadGrant(consume));
      assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failed.code());
      assertEquals(
          "CONSUMED",
          db.jdbc().queryForObject("SELECT status FROM private_asset_read_grant", String.class));
      assertEquals(
          "FAILED",
          db.jdbc()
              .queryForObject(
                  "SELECT result FROM private_asset_read_audit WHERE action='CONSUME' ORDER BY id"
                      + " DESC LIMIT 1",
                  String.class));
      ApiException second = assertThrows(ApiException.class, () -> api.consumeReadGrant(consume));
      assertEquals(PrivateAssetApiCodes.GRANT_GONE, second.code());
      var ambient = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
      ApiException nested =
          assertThrows(
              ApiException.class,
              () ->
                  ambient.execute(
                      status -> {
                        api.consumeReadGrant(consume);
                        return null;
                      }));
      assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, nested.code());
    }
  }

  @Test
  void onlyOneConcurrentConsumeSucceedsAndExpiredGrantIsGone() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var store = new MemoryStore();
      var api = fixture(db, store, Clock.systemUTC());
      UploadPrivateAssetResult uploaded =
          api.upload(upload("00000000-0000-0000-0000-000000000022"));
      IssuedPrivateAssetReadGrant issued =
          api.issueReadGrant(
              new IssuePrivateAssetReadGrantCommand(
                  uploaded.assetId(),
                  "901",
                  "902",
                  "MERCHANT_APPLICATION_REVIEW",
                  "review",
                  "session-2",
                  5,
                  context(
                      "00000000-0000-0000-0000-000000000023",
                      OperatorType.PLATFORM_OPERATOR,
                      "77")));
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        var calls =
            List.of(
                CompletableFuture.supplyAsync(
                    () -> consumeCode(api, issued.token(), "concurrent-a"), pool),
                CompletableFuture.supplyAsync(
                    () -> consumeCode(api, issued.token(), "concurrent-b"), pool));
        List<String> results = calls.stream().map(CompletableFuture::join).toList();
        assertEquals(1, results.stream().filter("SUCCESS"::equals).count());
        assertEquals(1, results.stream().filter(PrivateAssetApiCodes.GRANT_GONE::equals).count());
      } finally {
        pool.shutdownNow();
      }

      IssuedPrivateAssetReadGrant expiring =
          api.issueReadGrant(
              new IssuePrivateAssetReadGrantCommand(
                  uploaded.assetId(),
                  "901",
                  "902",
                  "MERCHANT_APPLICATION_REVIEW",
                  "expiry",
                  "session-2",
                  5,
                  context(
                      "00000000-0000-0000-0000-000000000024",
                      OperatorType.PLATFORM_OPERATOR,
                      "77")));
      db.jdbc()
          .update(
              "UPDATE private_asset_read_grant SET"
                  + " issued_at=TIMESTAMPADD(MINUTE,-6,UTC_TIMESTAMP(3)),"
                  + " expires_at=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3)) WHERE status='ISSUED'");
      ApiException gone =
          assertThrows(
              ApiException.class,
              () ->
                  api.consumeReadGrant(
                      new ConsumePrivateAssetReadGrantCommand(
                          expiring.token(),
                          "session-2",
                          5,
                          context("expired", OperatorType.PLATFORM_OPERATOR, "77"))));
      assertEquals(PrivateAssetApiCodes.GRANT_GONE, gone.code());
    }
  }

  private static String consumeCode(PrivateAssetApiImpl api, String token, String requestId) {
    try {
      api.consumeReadGrant(
          new ConsumePrivateAssetReadGrantCommand(
              token, "session-2", 5, context(requestId, OperatorType.PLATFORM_OPERATOR, "77")));
      return "SUCCESS";
    } catch (ApiException failure) {
      return failure.code();
    }
  }

  @Test
  void participatingAuthorizationRollbackCannotReviveConsumedGrant() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var store = new MemoryStore();
      var deny = new AtomicBoolean();
      var participating =
          new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
      var api =
          fixture(
              db,
              store,
              Clock.systemUTC(),
              request ->
                  participating.execute(
                      status -> {
                        if (deny.get()) throw new ApiException(CommonApiCodes.FORBIDDEN, "revoked");
                        return proof(request, store);
                      }));
      UploadPrivateAssetResult uploaded =
          api.upload(upload("00000000-0000-0000-0000-000000000012"));
      var issued =
          api.issueReadGrant(
              new IssuePrivateAssetReadGrantCommand(
                  uploaded.assetId(),
                  "901",
                  "902",
                  "MERCHANT_APPLICATION_REVIEW",
                  "review evidence",
                  "session-1",
                  4,
                  context(
                      "00000000-0000-0000-0000-000000000013",
                      OperatorType.PLATFORM_OPERATOR,
                      "77")));
      deny.set(true);
      var consume =
          new ConsumePrivateAssetReadGrantCommand(
              issued.token(),
              "session-1",
              4,
              context("consume-denied", OperatorType.PLATFORM_OPERATOR, "77"));
      ApiException denied = assertThrows(ApiException.class, () -> api.consumeReadGrant(consume));
      assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
      assertEquals(
          "CONSUMED",
          db.jdbc().queryForObject("SELECT status FROM private_asset_read_grant", String.class));
      assertEquals(
          "DENIED",
          db.jdbc()
              .queryForObject(
                  "SELECT result FROM private_asset_read_audit WHERE action='CONSUME' ORDER BY id"
                      + " DESC LIMIT 1",
                  String.class));
    }
  }

  @Test
  void undecodableImageIsRejectedAndNeverBecomesReady() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var store = new MemoryStore();
      var api =
          fixture(
              db,
              store,
              Clock.systemUTC(),
              request -> proof(request, store),
              (bytes, mediaType) -> {
                throw new IllegalArgumentException("not an image");
              });
      ApiException rejected =
          assertThrows(
              ApiException.class, () -> api.upload(upload("00000000-0000-0000-0000-000000000032")));
      assertEquals(PrivateAssetApiCodes.ASSET_REJECTED, rejected.code());
      assertEquals(
          "REJECTED", db.jdbc().queryForObject("SELECT status FROM private_asset", String.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM private_asset WHERE status='READY'", Integer.class));
    }
  }

  @Test
  void ownerQueryIsAllOrNothingAndUploadConflictCannotReplaceAsset() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var store = new MemoryStore();
      var api =
          fixture(
              db,
              store,
              Clock.systemUTC(),
              request -> proof(request, store),
              (bytes, mediaType) -> {
                byte[] normalized = Arrays.copyOf(bytes, bytes.length + 1);
                normalized[normalized.length - 1] = 99;
                return new PrivateAssetImageNormalizer.NormalizedImage(
                    normalized, "image/png", 10, 10);
              });
      String request = "00000000-0000-0000-0000-000000000042";
      UploadPrivateAssetResult own = api.upload(upload("42", request, SOURCE));
      UploadPrivateAssetResult other =
          api.upload(
              upload(
                  "43",
                  "00000000-0000-0000-0000-000000000043",
                  "other-image".getBytes(StandardCharsets.UTF_8)));

      List<PrivateAssetFact> facts =
          api.resolveOwned(
              new ResolveOwnedPrivateAssetsQuery("42", List.of(own.assetId()), PURPOSE));
      assertEquals(1, facts.size());
      assertEquals(own.objectSha256(), facts.getFirst().objectSha256());
      assertNotEquals(facts.getFirst().sourceSha256(), facts.getFirst().objectSha256());

      assertEquals(
          CommonApiCodes.NOT_FOUND,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.resolveOwned(
                          new ResolveOwnedPrivateAssetsQuery(
                              "42", List.of(own.assetId(), other.assetId()), PURPOSE)))
              .code());
      assertEquals(
          CommonApiCodes.NOT_FOUND,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.resolveOwned(
                          new ResolveOwnedPrivateAssetsQuery(
                              "42", List.of(own.assetId(), "999999"), PURPOSE)))
              .code());
      assertEquals(
          CommonApiCodes.INVALID_ARGUMENT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.resolveOwned(
                          new ResolveOwnedPrivateAssetsQuery(
                              "42", List.of(own.assetId(), own.assetId()), PURPOSE)))
              .code());

      ApiException changedBytes =
          assertThrows(
              ApiException.class,
              () ->
                  api.upload(
                      upload("42", request, "changed-image".getBytes(StandardCharsets.UTF_8))));
      assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, changedBytes.code());
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM private_asset_upload_request WHERE owner_user_id=42",
                  Integer.class));
      assertEquals(
          own.assetId(),
          db.jdbc()
              .queryForObject(
                  "SELECT CAST(asset_id AS CHAR) FROM private_asset_upload_request WHERE"
                      + " owner_user_id=42",
                  String.class));
    }
  }

  private static PrivateAssetApiImpl fixture(
      PrivateAssetMySqlTestDatabase db, MemoryStore store, Clock clock) {
    return fixture(db, store, clock, request -> proof(request, store));
  }

  private static PrivateAssetApiImpl fixture(
      PrivateAssetMySqlTestDatabase db,
      MemoryStore store,
      Clock clock,
      com.petplatform.thirdparty.api.PrivateAssetReadAuthorizer authorizer) {
    return fixture(
        db,
        store,
        clock,
        authorizer,
        (bytes, mediaType) ->
            new PrivateAssetImageNormalizer.NormalizedImage(bytes.clone(), "image/png", 10, 10));
  }

  private static PrivateAssetApiImpl fixture(
      PrivateAssetMySqlTestDatabase db,
      MemoryStore store,
      Clock clock,
      com.petplatform.thirdparty.api.PrivateAssetReadAuthorizer authorizer,
      PrivateAssetImageNormalizer normalizer) {
    AtomicLong ids = new AtomicLong(10_000);
    return new PrivateAssetApiImpl(
        db.dataSource(),
        ids::incrementAndGet,
        store,
        bytes -> new PrivateAssetScanner.ScanResult(true, "test-scanner-v1", "CLEAN"),
        normalizer,
        (bytes, mediaType, watermark) ->
            new PrivateAssetWatermarkRenderer.RenderedImage(bytes.clone(), "image/png"),
        authorizer,
        () ->
            new PrivateAssetGrantKeyProvider.KeyMaterial(
                "grant-v1", new SecretKeySpec(new byte[32], "HmacSHA256")),
        (purpose, plaintext) -> (purpose + ":" + plaintext).getBytes(StandardCharsets.UTF_8),
        clock);
  }

  private static ReadAuthorizationProof proof(ReadAuthorizationRequest request, MemoryStore store) {
    return new ReadAuthorizationProof(
        "903",
        "BUSINESS_LICENSE",
        "42",
        request.assetId(),
        store.finalHash(),
        request.revisionId(),
        "authz-v1",
        "scope-v1");
  }

  private static UploadPrivateAssetCommand upload(String requestId) {
    return upload("42", requestId, SOURCE);
  }

  private static UploadPrivateAssetCommand upload(String owner, String requestId, byte[] content) {
    return new UploadPrivateAssetCommand(
        owner,
        PURPOSE,
        "image/png",
        content.length,
        new ByteArrayInputStream(content),
        context(requestId, OperatorType.USER, owner));
  }

  private static CommandContext context(String requestId, OperatorType type, String operator) {
    return new CommandContext(requestId, "trace-test", type, operator, "TEST");
  }

  private static final class MemoryStore implements PrivateObjectStore {
    private final Map<String, StoredContent> values = new HashMap<>();
    boolean loseFirstPutAck;
    boolean failGet;
    int putCalls;

    @Override
    public StoredObject putIfAbsent(String key, byte[] content, String type, String hash) {
      putCalls++;
      StoredContent existing = values.putIfAbsent(key, new StoredContent(content, type, hash));
      StoredContent stored = existing == null ? values.get(key) : existing;
      if (!stored.sha256().equals(hash)) throw new IllegalStateException("immutable mismatch");
      if (loseFirstPutAck) {
        loseFirstPutAck = false;
        throw new IllegalStateException("ack lost");
      }
      return new StoredObject("etag:" + hash, hash, stored.content().length, stored.mediaType());
    }

    @Override
    public StoredContent get(String key, String version) {
      if (failGet) throw new IllegalStateException("storage unavailable");
      return values.get(key);
    }

    @Override
    public Optional<StoredObject> head(String key) {
      StoredContent content = values.get(key);
      return content == null
          ? Optional.empty()
          : Optional.of(
              new StoredObject(
                  "etag:" + content.sha256(),
                  content.sha256(),
                  content.content().length,
                  content.mediaType()));
    }

    String finalHash() {
      return values.entrySet().stream()
          .filter(entry -> entry.getKey().endsWith("normalized-v1"))
          .map(entry -> entry.getValue().sha256())
          .findFirst()
          .orElseThrow();
    }
  }
}
