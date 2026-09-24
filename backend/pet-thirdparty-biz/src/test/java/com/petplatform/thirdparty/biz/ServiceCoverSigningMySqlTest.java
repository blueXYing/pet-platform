package com.petplatform.thirdparty.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.*;
import com.petplatform.thirdparty.biz.apiimpl.ServiceCoverSigningApiImpl;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServiceCoverSigningMySqlTest {
  private final QueryContext context = new QueryContext("test", OperatorType.SYSTEM, "test");

  @Test
  void onlyReadyServiceCoversReachTheSignerAndPointersStayPrivate() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      var repository = new PrivateAssetRepository(db.dataSource());
      repository.transaction(m -> {
        for (long id = 101; id <= 104; id++) {
          m.insertAsset(id, 77, id == 102 ? "MERCHANT_APPLICATION_MATERIAL" : "SERVICE_COVER",
              "merchant-materials/77/" + id + "/source", "merchant-materials/77/" + id + "/normalized-v1", "a".repeat(64));
          m.updateSourceStored(id, "version:source1");
          m.updateScanning(id);
          if (id != 103) m.updateReady(id, "version:final1", "b".repeat(64), "image/png", 100, "test-scanner", "CLEAN");
        }
        return null;
      });
      AtomicInteger calls = new AtomicInteger();
      var api = new ServiceCoverSigningApiImpl(db.dataSource(), (key, version) -> {
        calls.incrementAndGet();
        assertEquals("merchant-materials/77/101/normalized-v1", key);
        assertEquals("version:final1", version);
        return new ServiceCoverObjectSigner.SignedObject("https://test.example.invalid/signed", Instant.now().plusSeconds(600));
      }, Clock.systemUTC());
      assertEquals("101", api.signServiceCover("101", context).assetId());
      for (String id : new String[] {"102", "103", "999", "-1", "000101"}) {
        ApiException failure = assertThrows(ApiException.class, () -> api.signServiceCover(id, context));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
      }
      assertEquals(1, calls.get(), "identity material and non-ready/missing assets must never reach signing");
      db.jdbc().update("UPDATE private_asset SET object_key=source_object_key WHERE id=104");
      assertThrows(ApiException.class, () -> api.signServiceCover("104", context));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void providerFailureOrExpiredResponseFailsClosedWithoutProviderDetails() throws Exception {
    try (var db = new PrivateAssetMySqlTestDatabase()) {
      new PrivateAssetRepository(db.dataSource()).transaction(m -> {
        m.insertAsset(101, 77, "SERVICE_COVER", "merchant-materials/77/101/source",
            "merchant-materials/77/101/normalized-v1", "a".repeat(64));
        m.updateSourceStored(101, "version:source1"); m.updateScanning(101);
        m.updateReady(101, "version:final1", "b".repeat(64), "image/png", 100, "test", "CLEAN");
        return null;
      });
      var api = new ServiceCoverSigningApiImpl(db.dataSource(), (k, v) -> { throw new IllegalStateException("sensitive provider detail"); }, Clock.systemUTC());
      var error = assertThrows(ApiException.class, () -> api.signServiceCover("101", context));
      assertFalse(error.toString().contains("sensitive")); assertNull(error.getCause());
      var expired = new ServiceCoverSigningApiImpl(db.dataSource(), (k, v) -> new ServiceCoverObjectSigner.SignedObject("https://test.example.invalid/x", Instant.EPOCH), Clock.systemUTC());
      assertThrows(ApiException.class, () -> expired.signServiceCover("101", context));
    }
  }
}
