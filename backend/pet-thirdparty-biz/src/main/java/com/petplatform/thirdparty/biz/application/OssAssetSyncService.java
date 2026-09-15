package com.petplatform.thirdparty.biz.application;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssAssetClient;
import com.petplatform.thirdparty.biz.infrastructure.persistence.AssetRegistryJdbcStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * One-click re-import (CCR-OSS-001 decision 2, rerunnable by design): rescans the
 * local design package roots, uploads new or changed content (content-addressed
 * object keys make repeats no-ops), refreshes registry URLs, and retires keys
 * that vanished from the source. Registry rows are never deleted.
 */
public final class OssAssetSyncService {
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "webp", "image/webp", "gif", "image/gif", "svg", "image/svg+xml");

    public record SyncReport(int uploaded, int unchanged, int retired, List<String> errors) {
        public int scanned() {
            return uploaded + unchanged;
        }
    }

    private final OssAssetClient client;
    private final AssetRegistryJdbcStore registry;

    public OssAssetSyncService(OssAssetClient client, DataSource dataSource, SnowflakeIdGenerator ids) {
        this.client = client;
        this.registry = new AssetRegistryJdbcStore(dataSource, ids);
    }

    /** @param roots category → directory; every supported image ≥ minBytes inside is a candidate. */
    public SyncReport sync(Map<String, Path> roots, long minBytes) {
        int uploaded = 0;
        int unchanged = 0;
        int retiredTotal = 0;
        List<String> errors = new ArrayList<>();
        for (var entry : roots.entrySet()) {
            String category = entry.getKey();
            Path root = entry.getValue();
            Set<String> present = new HashSet<>();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String ext = extension(file);
                    if (!CONTENT_TYPES.containsKey(ext)) continue;
                    long size;
                    byte[] content;
                    try {
                        size = Files.size(file);
                        if (size < minBytes) continue;
                        content = Files.readAllBytes(file);
                    } catch (IOException error) {
                        errors.add(file + ": read failed");
                        continue;
                    }
                    String sha = sha256Hex(content);
                    String objectKey = "assets/" + sha + "." + ext;
                    String assetKey = assetKey(category, root, file);
                    present.add(assetKey);
                    try {
                        if (client.exists(objectKey)) {
                            unchanged++;
                        } else {
                            client.put(objectKey, content, CONTENT_TYPES.get(ext));
                            uploaded++;
                        }
                        registry.upsertActive(new AssetRegistryJdbcStore.AssetRow(
                                assetKey, objectKey, client.publicUrl(objectKey), sha, size, category));
                    } catch (RuntimeException error) {
                        errors.add(file + ": " + error.getClass().getSimpleName());
                    }
                }
            } catch (IOException error) {
                errors.add(root + ": scan failed");
            }
            retiredTotal += registry.retireAbsent(category, present);
        }
        return new SyncReport(uploaded, unchanged, retiredTotal, errors);
    }

    private static String assetKey(String category, Path root, Path file) {
        return category + "/" + root.relativize(file).toString().replace('\\', '/');
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
