package com.petplatform.marketing.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.AssetRow;
import com.petplatform.marketing.biz.infrastructure.persistence.mapper.MarketingMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Shared campaign-artwork write path (contract 71 §4.5 / 76 §4.2). The platform-ops upload and
 * the merchant cover upload run the same sniff/size/pixel checks, the same OSS-or-blob storage
 * decision and the same row+audit writes; only the ownership columns and the idempotency key
 * differ. Extracted so admin and merchant can never drift into two validation copies.
 */
final class MarketingAssetWriter {

    /** Server-validated upload payload; content is the raw (unmodified) file bytes. */
    record Validated(String contentType, int width, int height, String sha256, byte[] content) {}

    private final MarketingStore store;
    private final MarketingAssetObjectStore assetObjectStore;

    MarketingAssetWriter(MarketingStore store, MarketingAssetObjectStore assetObjectStore) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.assetObjectStore = Objects.requireNonNull(assetObjectStore, "assetObjectStore is required");
    }

    /**
     * Contract 76 §4.2: media type is sniffed from the magic bytes, never taken from the
     * client-declared Content-Type; PNG/JPEG only, non-empty, ≤2 MiB, 1..2000 pixels.
     */
    static Validated validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ApiException("MARKETING_ASSET_INVALID", "素材文件为空");
        }
        if (content.length > 2 * 1024 * 1024) {
            throw new ApiException("MARKETING_ASSET_TOO_LARGE", "素材不能超过 2MiB");
        }
        String type = sniff(content);
        if (type == null) {
            throw new ApiException("MARKETING_ASSET_INVALID", "仅支持 PNG/JPEG 图片");
        }
        java.awt.image.BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException failure) {
            throw new ApiException("MARKETING_ASSET_INVALID", "图片内容不可读取");
        }
        if (image == null) {
            throw new ApiException("MARKETING_ASSET_INVALID", "图片内容不可读取");
        }
        if (image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > 2000 || image.getHeight() > 2000) {
            throw new ApiException("MARKETING_ASSET_INVALID", "图片尺寸必须在 1..2000 之间");
        }
        return new Validated(type, image.getWidth(), image.getHeight(), sha256(content), content);
    }

    /** PNG/JPEG magic-byte sniff; everything else is rejected without trusting any header. */
    private static String sniff(byte[] content) {
        if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P'
                && content[2] == 'N' && content[3] == 'G' && content[4] == '\r'
                && content[5] == '\n' && (content[6] & 0xFF) == 0x1A && content[7] == '\n') {
            return "image/png";
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    /**
     * Runs inside the caller's idempotent transaction: places the bytes (OSS when configured,
     * DB blob otherwise), writes {@code marketing_asset} with the caller's ownership columns and
     * appends the {@code marketing_asset_audit} row.
     */
    MarketingViews.AssetView persist(
            MarketingMapper mapper,
            Validated asset,
            String ownerType,
            Long ownerMerchantId,
            Long ownerStoreId,
            String purpose,
            long operatorId,
            CommandContext context) {
        AssetRow row = new AssetRow();
        row.id = store.nextId();
        row.ownerType = ownerType;
        row.ownerMerchantId = ownerMerchantId;
        row.ownerStoreId = ownerStoreId;
        row.purpose = purpose;
        row.contentType = asset.contentType();
        row.bytes = (long) asset.content().length;
        row.width = asset.width();
        row.height = asset.height();
        row.sha256 = asset.sha256();
        if (assetObjectStore.enabled()) {
            String extension = "image/jpeg".equals(asset.contentType()) ? "jpg" : "png";
            row.objectKey = "marketing-assets/" + Long.toUnsignedString(row.id) + "." + extension;
            try {
                assetObjectStore.put(row.objectKey, asset.content(), asset.contentType());
            } catch (RuntimeException failure) {
                MarketingSupport.unavailable("素材对象存储不可用");
                return null;
            }
            row.content = null;
        } else {
            row.content = asset.content();
        }
        row.status = "ACTIVE";
        row.createdById = operatorId;
        row.createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (mapper.insertAsset(row) != 1) MarketingSupport.unavailable("素材写入失败");
        if (mapper.insertAssetAudit(store.nextId(), row.id, "UPLOAD",
                row.createdById, context.requestId(), context.traceId(), row.createdAt) != 1) {
            MarketingSupport.unavailable("素材审计写入失败");
        }
        return new MarketingViews.AssetView(Long.toUnsignedString(row.id),
                row.contentType, row.bytes, row.width, row.height, row.sha256,
                offset(row.createdAt), row.objectKey);
    }

    private static OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static MarketingViews.AssetView parseAsset(String json) {
        try {
            var root = MarketingSupport.JSON.readTree(json);
            // objectKey 是 2026-11-24 追加的字段：此前落库的幂等回执里没有它，按缺省（null）读回。
            return new MarketingViews.AssetView(root.path("assetId").asText(),
                    root.path("contentType").asText(), root.path("bytes").asLong(),
                    root.path("width").asInt(), root.path("height").asInt(),
                    root.path("sha256").asText(),
                    OffsetDateTime.parse(root.path("createdAt").asText()),
                    root.path("objectKey").asText(null));
        } catch (Exception failure) {
            MarketingSupport.unavailable("素材回执不可读取");
            return null;
        }
    }

    static String assetJson(MarketingViews.AssetView asset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.assetId());
        out.put("contentType", asset.contentType());
        out.put("bytes", asset.bytes());
        out.put("width", asset.width());
        out.put("height", asset.height());
        out.put("sha256", asset.sha256());
        out.put("createdAt", asset.createdAt().toString());
        out.put("objectKey", asset.objectKey());
        try {
            return MarketingSupport.JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("asset receipt serialization failed", failure);
        }
    }

    static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
