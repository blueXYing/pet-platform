package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.petplatform.payment.biz.settlement.MerchantOnboardingService.NotificationVerifier;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.ContractProgress;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindNotification;
import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies the 1009 three-line signature of every onboarding notification with the trusted Lakala
 * notification key before any field is read, then parses the business payload (CCR-PAY-ONBOARD-001 §6).
 */
public final class LakalaOnboardingNotifications implements NotificationVerifier {
    private final PublicKey trustedKey;

    public LakalaOnboardingNotifications(PublicKey trustedNotificationKey) {
        this.trustedKey = Objects.requireNonNull(trustedNotificationKey, "trustedNotificationKey");
    }

    @Override
    public EcNotice ec(Map<String, String> headers, byte[] rawBody) {
        JsonNode payload = LakalaV2Protocol.verifyV2Notification(headers, rawBody, trustedKey);
        var status = LakalaOnboardingProtocol.parseEcNotification(payload);
        return new EcNotice(status.ecApplyId(), status.orderNo(), status.completed(), status.ecNo());
    }

    @Override
    public ContractProgress contract(Map<String, String> headers, byte[] rawBody) {
        JsonNode payload = LakalaV2Protocol.verifyV2Notification(headers, rawBody, trustedKey);
        var info = LakalaOnboardingProtocol.parseContractNotification(payload);
        return new ContractProgress(info.contractId(), info.contractStatus(), info.contractMemo(),
                info.merInnerNo(), info.merCupNo(), info.firstTermNo());
    }

    @Override
    public LedgerOpenNotice ledgerOpen(Map<String, String> headers, byte[] rawBody) {
        JsonNode payload = LakalaV2Protocol.verifyV2Notification(headers, rawBody, trustedKey);
        var notice = LakalaOnboardingProtocol.parseLedgerOpenNotification(payload);
        return new LedgerOpenNotice(notice.applyId(), notice.merCupNo(), notice.approved(),
                notice.remark() == null ? notice.auditStatusText() : notice.remark());
    }

    @Override
    public BindNotice bind(Map<String, String> headers, byte[] rawBody) {
        BindNotification bind = LakalaV2Protocol.verifyBindNotification(headers, rawBody, trustedKey);
        return new BindNotice(bind.applyId(), bind.merCupNo(), bind.receiverNo(),
                bind.auditStatus() == LakalaV2Protocol.BindAuditStatus.APPROVED, bind.remark());
    }

    /** Fail-closed verifier used when no trusted notification certificate is configured. */
    public static NotificationVerifier failClosed() {
        return new NotificationVerifier() {
            @Override
            public EcNotice ec(Map<String, String> headers, byte[] rawBody) {
                throw unavailable();
            }

            @Override
            public ContractProgress contract(Map<String, String> headers, byte[] rawBody) {
                throw unavailable();
            }

            @Override
            public LedgerOpenNotice ledgerOpen(Map<String, String> headers, byte[] rawBody) {
                throw unavailable();
            }

            @Override
            public BindNotice bind(Map<String, String> headers, byte[] rawBody) {
                throw unavailable();
            }

            private com.petplatform.common.ApiException unavailable() {
                return new com.petplatform.common.ApiException(
                        com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        "拉卡拉回调验签证书未配置");
            }
        };
    }
}
