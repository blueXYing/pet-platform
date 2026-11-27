package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import java.io.InputStream;

/**
 * Port for storing one operator-uploaded onboarding attachment in the platform's private-asset store
 * (contract 81 §4.12). Returns the private asset id; the bytes ride the existing scan/normalize pipeline
 * and are read back through {@link ReceiverAttachmentProvider} right before the Lakala 1084 upload.
 */
@FunctionalInterface
public interface OnboardingAttachmentUploader {

    String upload(String ownerUserId, String declaredMediaType, long declaredBytes, InputStream content,
            CommandContext context);

    /** Fail-closed uploader when no private-asset store is wired. */
    static OnboardingAttachmentUploader failClosed() {
        return (owner, type, bytes, content, context) -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "私有素材存储未启用，无法上传开通附件");
        };
    }
}
