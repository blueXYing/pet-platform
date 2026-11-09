package com.petplatform.thirdparty.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.*;
import com.petplatform.thirdparty.api.StorePhotoSigningApi;
import com.petplatform.thirdparty.biz.apiimpl.StorePhotoSigningApiImpl;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * CCR-M-STORE-AVATAR-001: the owner-checked STORE_PHOTO signing face. The requesting OWNER's
 * identity, the STORE_PHOTO purpose, READY, image type and the versioned normalized object are
 * all re-proven inside the signing transaction; anything else fails closed without provider
 * details and the object pointers never leave the module.
 */
class StorePhotoOwnerSigningMySqlTest {
  private final QueryContext context = new QueryContext("test", OperatorType.USER, "77");

  @Test
  void ownerMatchSignsAndAnyOtherOwnerFailsClosed() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      seedReadyStorePhoto(db, 201, 77);
      AtomicInteger calls = new AtomicInteger();
      var api = new StorePhotoSigningApiImpl(db.dataSource(), (key, version) -> {
        calls.incrementAndGet();
        assertEquals("merchant-materials/77/201/normalized-v1", key);
        assertEquals("version:final1", version);
        return new ServiceCoverObjectSigner.SignedObject("https://test.example.invalid/signed",
            Instant.now().plusSeconds(600));
      }, Clock.systemUTC());
      StorePhotoSigningApi.SignedStorePhoto signed = api.signOwnedStorePhoto("77", "201", context);
      assertEquals("201", signed.assetId());
      assertEquals("https://test.example.invalid/signed", signed.signedUrl());
      assertNotNull(signed.expiresAt());
      // Another account (or a damaged/foreign profile reference) must never sign.
      for (String owner : new String[] {"78", "0", "-1", null, "", "  "}) {
        ApiException failure =
            assertThrows(ApiException.class, () -> api.signOwnedStorePhoto(owner, "201", context));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
      }
      for (String asset : new String[] {"202", "999", "-1", "000201", null}) {
        assertThrows(ApiException.class, () -> api.signOwnedStorePhoto("77", asset, context));
      }
      assertThrows(ApiException.class, () -> api.signOwnedStorePhoto("77", "201", null));
      assertEquals(1, calls.get(), "only the owner-matched READY asset reaches the signer");
    }
  }

  @Test
  void onlyReadyStorePhotoPurposeReachesTheOwnerSigner() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var repository = new PrivateAssetRepository(db.dataSource());
      repository.transaction(m -> {
        // Seed 301..303 only: 304 must stay a genuinely missing asset for the negative loop.
        for (long id = 301; id <= 303; id++) {
          m.insertAsset(id, 77, id == 302 ? "SERVICE_COVER" : "STORE_PHOTO",
              "merchant-materials/77/" + id + "/source",
              "merchant-materials/77/" + id + "/normalized-v1", "a".repeat(64));
          m.updateSourceStored(id, "version:source1");
          m.updateScanning(id);
          if (id != 303) m.updateReady(id, "version:final1", "b".repeat(64), "image/png", 100, "test", "CLEAN");
        }
        return null;
      });
      AtomicInteger calls = new AtomicInteger();
      var api = new StorePhotoSigningApiImpl(db.dataSource(), (key, version) -> {
        calls.incrementAndGet();
        return new ServiceCoverObjectSigner.SignedObject("https://test.example.invalid/signed",
            Instant.now().plusSeconds(600));
      }, Clock.systemUTC());
      assertEquals("301", api.signOwnedStorePhoto("77", "301", context).assetId());
      // 302 is a cover, 303 never reached READY, 304 does not exist.
      for (String id : new String[] {"302", "303", "304"}) {
        ApiException failure = assertThrows(ApiException.class, () -> api.signOwnedStorePhoto("77", id, context));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
      }
      assertEquals(1, calls.get());
      // An unversioned (ETag-only) object can never be signed as the store avatar. A READY row
      // can never carry a NULL object_version_ref (schema CHECK private_asset_chk_3), so this
      // negative uses the versioned-object rule's other half: point the key back at the source
      // key so the /normalized-v1 suffix guard rejects signing.
      db.jdbc().update(
          "UPDATE private_asset SET object_key='merchant-materials/77/301/source' WHERE id=301");
      assertThrows(ApiException.class, () -> api.signOwnedStorePhoto("77", "301", context));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void providerFailureOrExpiredResponseFailsClosedWithoutProviderDetails() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      seedReadyStorePhoto(db, 401, 77);
      var api = new StorePhotoSigningApiImpl(db.dataSource(), (k, v) -> {
        throw new IllegalStateException("sensitive provider detail");
      }, Clock.systemUTC());
      var error = assertThrows(ApiException.class, () -> api.signOwnedStorePhoto("77", "401", context));
      assertFalse(error.toString().contains("sensitive"));
      assertNull(error.getCause());
      var expired = new StorePhotoSigningApiImpl(db.dataSource(), (k, v) ->
          new ServiceCoverObjectSigner.SignedObject("https://test.example.invalid/x", Instant.EPOCH),
          Clock.systemUTC());
      assertThrows(ApiException.class, () -> expired.signOwnedStorePhoto("77", "401", context));
      // A null signer (signing assembly without the OSS keys) also fails closed, never a bare URL.
      var unconfigured = new StorePhotoSigningApiImpl(db.dataSource(), null, Clock.systemUTC());
      assertThrows(ApiException.class, () -> unconfigured.signOwnedStorePhoto("77", "401", context));
    }
  }

  private static void seedReadyStorePhoto(PrivateAssetMySqlTestDatabase db, long id, long owner)
      throws Exception {
    new PrivateAssetRepository(db.dataSource()).transaction(m -> {
      m.insertAsset(id, owner, "STORE_PHOTO", "merchant-materials/" + owner + "/" + id + "/source",
          "merchant-materials/" + owner + "/" + id + "/normalized-v1", "a".repeat(64));
      m.updateSourceStored(id, "version:source1");
      m.updateScanning(id);
      m.updateReady(id, "version:final1", "b".repeat(64), "image/png", 100, "test-scanner", "CLEAN");
      return null;
    });
  }
}
