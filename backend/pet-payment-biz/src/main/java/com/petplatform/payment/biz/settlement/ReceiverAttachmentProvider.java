package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol;

/**
 * Port for reading platform private-asset bytes right before submitting Lakala 1084 upload +
 * 1287/1288/1290 calls so transient Lakala {@code attFileId} values stay within the 24h {@code orderNo}
 * window and are never persisted long-term.
 */
@FunctionalInterface
public interface ReceiverAttachmentProvider {
    AssetContent readAsset(String assetId);

    record AssetContent(String assetId, String extName, String fileName, byte[] bytes) {
        public AssetContent {
            if (assetId == null || assetId.isBlank()) {
                throw new IllegalArgumentException("assetId is blank");
            }
            if (extName == null || extName.isBlank()) {
                throw new IllegalArgumentException("extName is blank");
            }
            if (bytes == null || bytes.length == 0 || bytes.length > LakalaV2Protocol.MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("asset content is empty or exceeds 5MB");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "AssetContent[assetId=" + assetId + ",extName=" + extName + ",size=" + bytes.length + "]";
        }
    }

    /** Default fail-closed provider when no private-asset reader is wired. */
    static ReceiverAttachmentProvider failClosed() {
        return assetId -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "private asset reader is not configured for receiver attachments");
        };
    }
}
