package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.aftersale.biz.application.AfterSaleAesProtection;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.thirdparty.biz.infrastructure.provider.assetimage.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.sql.DataSource;

/** Real upload/normalization/intent/task/READY persistence; OSS and antivirus are test-only doubles. */
final class AfterSaleAssetFixture {
    final AtomicBoolean scannerAvailable = new AtomicBoolean(true);
    final AtomicBoolean scannerClean = new AtomicBoolean(true);
    final PrivateAssetApiImpl api;
    final PrivateObjectStore objectStore;
    final PrivateAssetGrantKeyProvider grantKeys;
    final PrivateAssetReasonProtector reasonProtector;
    final Map<String, PrivateObjectStore.StoredContent> objects = new ConcurrentHashMap<>();
    private final AfterSaleIdentityFixture identity;

    AfterSaleAssetFixture(DataSource source, SnowflakeIdGenerator ids, AfterSaleIdentityFixture identity) {
        this.identity = identity;
        var protection = new AfterSaleAesProtection(AfterSaleIdentityFixture.key(31));
        objectStore = new PrivateObjectStore() {
            public StoredObject putIfAbsent(String key, byte[] bytes, String type, String hash) {
                var candidate = new StoredContent(bytes.clone(), type, hash);
                var old = objects.putIfAbsent(key, candidate);
                var stored = old == null ? candidate : old;
                if (!hash.equals(stored.sha256()) || !Arrays.equals(bytes, stored.content()))
                    throw new IllegalStateException("Immutable QA object differs");
                return new StoredObject("version:qa-afs-1", hash, bytes.length, type);
            }
            public StoredContent get(String key, String version) {
                if (!"version:qa-afs-1".equals(version)) throw new IllegalStateException("Wrong object version");
                return Objects.requireNonNull(objects.get(key));
            }
            public Optional<StoredObject> head(String key) {
                var stored = objects.get(key);
                return stored == null ? Optional.empty() : Optional.of(new StoredObject(
                        "version:qa-afs-1", stored.sha256(), stored.content().length, stored.mediaType()));
            }
        };
        grantKeys = () -> new PrivateAssetGrantKeyProvider.KeyMaterial("qa-afs-grant", new SecretKeySpec(
                AfterSaleIdentityFixture.key(47), "HmacSHA256"));
        reasonProtector = (purpose, plaintext) -> protection.protect(purpose,
                plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        api = new PrivateAssetApiImpl(source, ids, objectStore, bytes -> {
            if (!scannerAvailable.get()) throw new IllegalStateException("QA scanner unavailable");
            return new PrivateAssetScanner.ScanResult(scannerClean.get(), "QA_ONLY_SCANNER", scannerClean.get() ? "CLEAN" : "INFECTED");
        }, new ImageIoPrivateAssetImageNormalizer(), new ImageIoPrivateAssetWatermarkRenderer(),
                request -> { throw new ApiException(CommonApiCodes.FORBIDDEN, "MER read grants cannot authorize AFS evidence"); },
                grantKeys, reasonProtector,
                Clock.systemUTC());
    }

    String upload(String userId) { return upload(userId, "AFTERSALE_EVIDENCE", UUID.randomUUID().toString()); }
    String upload(String userId, String purpose, String requestId) {
        identity.asUser(userId);
        assertEquals(userId, identity.users.resolveSession(identity.userTokens.get(userId)).userId());
        byte[] png = png();
        var command = new UploadPrivateAssetCommand(userId, purpose, "image/png", png.length,
                new ByteArrayInputStream(png), new CommandContext(requestId, "afs-qa", OperatorType.USER, userId, "MINIAPP"));
        var result = api.upload(command);
        assertEquals(PrivateAssetStatus.READY, result.status());
        assertEquals("image/png", result.mediaType());
        return result.assetId();
    }

    private static byte[] png() {
        try {
            BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, (x * 7 << 16) | (y * 9 << 8) | 0x55);
            var out = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", out));
            return out.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}
