package com.petplatform.thirdparty.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.thirdparty.biz.apiimpl.StorePhotoSigningApiImpl;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * B-CARD-02 avatar signing acceptance (contract 74 §3.4, the ServiceCoverSigningApiImpl pattern
 * applied to the shared 31号 pipeline's STORE_PHOTO purpose): only READY store photos with a
 * versioned normalized object sign, application/identity materials and covers can never be signed
 * here, a null signer or provider failure fails closed without provider details, and object
 * pointers never leave the module.
 */
class StorePhotoSigningMySqlTest {
  private final QueryContext context = new QueryContext("test", OperatorType.SYSTEM, "test");

  @Test
  void onlyReadyStorePhotosReachTheSignerAndPointersStayPrivate() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      new com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository(
              db.dataSource())
          .transaction(m -> {
            for (long id = 201; id <= 205; id++) {
              m.insertAsset(
                  id,
                  77,
                  switch ((int) id) {
                    case 202 -> "MERCHANT_APPLICATION_MATERIAL";
                    case 203 -> "SERVICE_COVER";
                    default -> "STORE_PHOTO";
                  },
                  "merchant-materials/77/" + id + "/source",
                  "merchant-materials/77/" + id + "/normalized-v1",
                  "a".repeat(64));
              m.updateSourceStored(id, "version:source1");
              m.updateScanning(id);
              // 204 stays un-READY on purpose; everything else reaches READY.
              if (id != 204) {
                m.updateReady(id, "version:final1", "b".repeat(64), "image/png", 100, "test", "CLEAN");
              }
            }
            return null;
          });
      AtomicInteger calls = new AtomicInteger();
      var api =
          new StorePhotoSigningApiImpl(
              db.dataSource(),
              (key, version) -> {
                calls.incrementAndGet();
                assertEquals("merchant-materials/77/201/normalized-v1", key);
                assertEquals("version:final1", version);
                return new ServiceCoverObjectSigner.SignedObject(
                    "https://test.example.invalid/signed", Instant.now().plusSeconds(600));
              },
              Clock.systemUTC());
      var signed = api.signStorePhoto("201", context);
      assertEquals("201", signed.assetId());
      assertEquals("https://test.example.invalid/signed", signed.signedUrl());
      for (String id : new String[] {"202", "203", "204", "999", "-1", "000201"}) {
        ApiException failure = assertThrows(ApiException.class, () -> api.signStorePhoto(id, context));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
      }
      assertEquals(1, calls.get(), "non-store-photo and non-ready assets must never reach signing");
    }
  }

  @Test
  void missingSignerOrProviderFailureFailsClosedWithoutProviderDetails() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      new com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository(
              db.dataSource())
          .transaction(m -> {
            m.insertAsset(
                301, 77, "STORE_PHOTO", "merchant-materials/77/301/source",
                "merchant-materials/77/301/normalized-v1", "a".repeat(64));
            m.updateSourceStored(301, "version:source1");
            m.updateScanning(301);
            m.updateReady(301, "version:final1", "b".repeat(64), "image/jpeg", 100, "test", "CLEAN");
            return null;
          });
      // Assembleable without an OSS connection: every attempt 503s, nothing unsigned escapes.
      var withoutSigner = new StorePhotoSigningApiImpl(db.dataSource(), null, Clock.systemUTC());
      ApiException missing =
          assertThrows(ApiException.class, () -> withoutSigner.signStorePhoto("301", context));
      assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, missing.code());

      var failingSigner =
          new StorePhotoSigningApiImpl(
              db.dataSource(),
              (key, version) -> {
                throw new IllegalStateException("oss sdk exploded");
              },
              Clock.systemUTC());
      ApiException failure =
          assertThrows(ApiException.class, () -> failingSigner.signStorePhoto("301", context));
      assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
      assertFalse(
          String.valueOf(failure.getMessage()).contains("oss sdk exploded"),
          "provider internals must never leak through the port boundary");

      // A signer that answers with an expired stamp is also refused.
      var staleSigner =
          new StorePhotoSigningApiImpl(
              db.dataSource(),
              (key, version) ->
                  new ServiceCoverObjectSigner.SignedObject(
                      "https://test.example.invalid/stale", Instant.now().minusSeconds(60)),
              Clock.systemUTC());
      assertEquals(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE,
          assertThrows(ApiException.class, () -> staleSigner.signStorePhoto("301", context)).code());
    }
  }
}
