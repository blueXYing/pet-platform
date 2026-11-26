package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contract 80 §4.2 & §5 split-receiver onboarding and read-only status DTOs.
 * All IDs are decimal strings (Snowflake BIGINT); sensitive values are redacted in {@code toString()}.
 */
public final class ReceiverTypes {
    private ReceiverTypes() {}

    /** Platform private-asset reference (never persists Lakala 24h {@code attFileId}). */
    public record ReceiverAttachmentRef(String attType, String assetId) {}

    /** Admin draft save, 1084+1287 sync create, or 1084+1288 sync modify command. */
    public record UpsertReceiverCommand(
            CommandContext context,
            String merchantId,
            boolean submitToChannel,
            String receiverName,
            String receiverType,
            String acctNo,
            String acctName,
            String acctTypeCode,
            String acctCertificateType,
            String acctCertificateNo,
            String acctOpenBankCode,
            String acctOpenBankName,
            String acctClearBankCode,
            String contactMobile,
            String licenseNo,
            String licenseName,
            String legalPersonName,
            String legalPersonCertificateType,
            String legalPersonCertificateNo,
            List<ReceiverAttachmentRef> attachments,
            String reason) {
        public UpsertReceiverCommand {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        @Override
        public String toString() {
            return "UpsertReceiverCommand[merchantId=" + merchantId
                    + ",submitToChannel=" + submitToChannel + ",redacted]";
        }
    }

    /** Admin 1084+1290 async ledger relation bind command. */
    public record BindReceiverCommand(
            CommandContext context,
            String merchantId,
            String cooperationAssetId,
            String remark) {}

    /** Audited admin reveal of plaintext sensitive receiver fields (PLATFORM_OPERATOR + reason). */
    public record RevealReceiverCommand(
            CommandContext context,
            String merchantId,
            String reason) {}

    /** Masked admin detail view (Contract 80 §4.2). */
    public record ReceiverAccountView(
            String receiverAccountId,
            String merchantId,
            String status,
            String receiverNo,
            String applyId,
            String settleType,
            String receiverName,
            String receiverType,
            String acctName,
            String acctNoMasked,
            String acctNoLast4,
            String acctTypeCode,
            String acctCertificateType,
            String acctCertificateNoMasked,
            String acctOpenBankCode,
            String acctOpenBankName,
            String acctClearBankCode,
            String contactMobileMasked,
            String licenseNoMasked,
            String licenseName,
            String legalPersonName,
            String legalPersonCertificateType,
            String legalPersonCertificateNoMasked,
            List<ReceiverAttachmentRef> attachments,
            String cooperationAssetId,
            String rejectReason,
            long version,
            OffsetDateTime lastSyncedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        public ReceiverAccountView {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /** Masked admin list summary row (Contract 80 §4.2). */
    public record ReceiverAccountSummary(
            String receiverAccountId,
            String merchantId,
            String status,
            String receiverNo,
            String applyId,
            String receiverName,
            String receiverType,
            String acctName,
            String acctNoMasked,
            String acctNoLast4,
            String acctOpenBankName,
            String contactMobileMasked,
            String rejectReason,
            OffsetDateTime updatedAt) {}

    /** Plaintext sensitive view returned only by audited {@code revealSensitive}. */
    public record ReceiverSensitiveView(
            String merchantId,
            String acctNo,
            String acctCertificateNo,
            String contactMobile,
            String licenseNo,
            String legalPersonCertificateNo) {
        @Override
        public String toString() {
            return "ReceiverSensitiveView[merchantId=" + merchantId + ",redacted]";
        }
    }

    /** Read-only merchant miniapp view (Contract 80 §5; merchants cannot self-edit receiver data). */
    public record MerchantReceiverStatusView(
            String status,
            String statusLabel,
            String receiverNo,
            String acctNameMasked,
            String acctNoLast4,
            String bankName,
            boolean active,
            String rejectReason,
            OffsetDateTime updatedAt) {}

    public record ListReceiversQuery(
            QueryContext context,
            String merchantId,
            String status,
            int page,
            int size) {}

    public record ReceiverPage(
            List<ReceiverAccountSummary> items,
            long total,
            int page,
            int size) {
        public ReceiverPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
