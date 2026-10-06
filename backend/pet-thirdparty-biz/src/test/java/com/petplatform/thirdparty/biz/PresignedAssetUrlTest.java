package com.petplatform.thirdparty.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.thirdparty.biz.application.LocalSequenceIdGenerator;
import com.petplatform.thirdparty.biz.application.PresignedAssetUrlService;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.thirdparty.biz.infrastructure.persistence.AssetRegistryStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Offline presign behaviour over a real registry fixture: determinism, quantized expiry, not-found. */
class PresignedAssetUrlTest {

    private static OssConnection connection() {
        return new OssConnection("https://oss-cn-test.aliyuncs.com", "cn-test",
                "test-bucket", "ak", "sk", null);
    }

    private static AssetRegistryStore seed(MySqlAssetRegistryTestDatabase db, String assetKey, String objectKey) {
        var store = new AssetRegistryStore(db.dataSource(), new LocalSequenceIdGenerator());
        store.upsertActive(new AssetRegistryStore.AssetRow(assetKey, objectKey,
                "https://canonical/" + objectKey, "a".repeat(64), 60000, "test"));
        return store;
    }

    @Test void quantizedExpiryAndDeterministicWithinWindow() throws Exception {
        try (var db = new MySqlAssetRegistryTestDatabase()) {
            seed(db, "test/banner", "assets/abc123.png");
            Clock fixed = Clock.fixed(Instant.ofEpochSecond(10_000), ZoneOffset.UTC);
            try (var service = new PresignedAssetUrlService(connection(), db.dataSource(),
                    new LocalSequenceIdGenerator(), fixed, 3600)) {
                var first = service.presign("test/banner");
                assertEquals(10_800, first.expiresAtEpochSeconds(), "expiry quantized to next 3600s boundary");
                assertTrue(first.url().contains("test-bucket.oss-cn-test.aliyuncs.com/assets/abc123.png"));
                assertTrue(first.url().contains("X-Amz-Signature="), "presigned query auth present");
                var second = service.presign("test/banner");
                assertEquals(first.expiresAtEpochSeconds(), second.expiresAtEpochSeconds(),
                        "same clock within window yields identical expiry");
                // S3Presigner stamps X-Amz-Date/X-Amz-Signature with the SDK's live clock at
                // signing time (the injected Clock only drives expiry quantization), so a
                // byte-identical URL is not guaranteed when two calls straddle a wall-clock
                // second. Determinism is asserted on everything except those two params.
                assertEquals(stripSigningTimeParams(first.url()), stripSigningTimeParams(second.url()),
                        "same clock within window yields identical URL apart from signing timestamp");
            }
        }
    }

    private static String stripSigningTimeParams(String url) {
        return url.replaceAll("[&?]X-Amz-(Date|Signature)=[^&]*", "");
    }

    @Test void lateInWindowJumpsToNextBoundary() throws Exception {
        try (var db = new MySqlAssetRegistryTestDatabase()) {
            seed(db, "test/late", "assets/late.png");
            // 10_700 is within 12.5% of the 10_800 boundary → must jump to 14_400.
            Clock late = Clock.fixed(Instant.ofEpochSecond(10_700), ZoneOffset.UTC);
            try (var service = new PresignedAssetUrlService(connection(), db.dataSource(),
                    new LocalSequenceIdGenerator(), late, 3600)) {
                assertEquals(14_400, service.presign("test/late").expiresAtEpochSeconds());
            }
        }
    }

    @Test void unknownOrRetiredAssetIsNotFound() throws Exception {
        try (var db = new MySqlAssetRegistryTestDatabase()) {
            var store = seed(db, "test/gone", "assets/gone.png");
            new org.springframework.jdbc.core.JdbcTemplate(db.dataSource())
                    .update("UPDATE asset_registry SET status='RETIRED' WHERE asset_key='test/gone'");
            try (var service = new PresignedAssetUrlService(connection(), db.dataSource(),
                    new LocalSequenceIdGenerator(), Clock.systemUTC(), 3600)) {
                ApiException retired = assertThrows(ApiException.class, () -> service.presign("test/gone"));
                assertEquals("COMMON_NOT_FOUND", retired.code());
                ApiException missing = assertThrows(ApiException.class, () -> service.presign("test/nope"));
                assertEquals("COMMON_NOT_FOUND", missing.code());
            }
        }
    }
}
