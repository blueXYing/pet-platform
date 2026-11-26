package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetContent;
import java.util.Locale;
import java.util.Objects;

/**
 * Bridges {@link ReceiverAttachmentProvider} (payment module port) to {@link PrivateAssetApi}
 * (thirdparty module owner) so Lakala 1084 attachment uploads read verified, scanned, un-watermarked
 * READY objects from the configured OSS private store without crossing biz module boundaries.
 */
final class PrivateAssetReceiverAttachmentAdapter implements ReceiverAttachmentProvider {
    private final PrivateAssetApi privateAssets;

    PrivateAssetReceiverAttachmentAdapter(PrivateAssetApi privateAssets) {
        this.privateAssets = Objects.requireNonNull(privateAssets, "privateAssets");
    }

    @Override
    public AssetContent readAsset(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "接收方附件 assetId 不能为空");
        }
        String normalizedId = assetId.trim();
        PrivateAssetContent content = privateAssets.readReadyForSystem(
                normalizedId, new QueryContext(null, OperatorType.SYSTEM, null));
        if (content == null || content.content() == null || content.content().length == 0) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "无法读取接收方私有附件内容: " + normalizedId);
        }
        byte[] bytes = content.content();
        if (bytes.length > LakalaV2Protocol.MAX_ATTACHMENT_BYTES) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,
                    "接收方附件超过拉卡拉 5MB 大小限制: " + normalizedId);
        }
        String extName = toLakalaExt(content.mediaType());
        return new AssetContent(normalizedId, extName, "asset-" + normalizedId + "." + extName, bytes);
    }

    private static String toLakalaExt(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "接收方附件缺少媒体类型");
        }
        String base = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (base) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,
                    "拉卡拉 1084 不支持的附件格式: " + base);
        };
    }
}
