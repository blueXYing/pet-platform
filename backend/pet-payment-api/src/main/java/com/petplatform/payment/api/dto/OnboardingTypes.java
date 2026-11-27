package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Contract 81 merchant acquiring onboarding (Lakala 进件 → 开通分账 → 绑定平台接收方) DTOs, phase 1
 * (operator-entered on behalf of the merchant, Ruling 44 §2). All IDs are decimal strings
 * (Snowflake BIGINT); sensitive values are redacted in {@code toString()} and in every view except
 * the audited reveal.
 */
public final class OnboardingTypes {
    private OnboardingTypes() {}

    // ---- Commands --------------------------------------------------------------------------

    /** Merchant subject as Lakala 1020 needs it; license number, legal person ID and mobile stay plaintext only here. */
    public record MerchantProfileInput(
            String merRegName,
            String merBizName,
            String merRegDistCode,
            String merRegAddr,
            String mccCode,
            String merBlisName,
            String merBlisNo,
            String merBlisStDt,
            String merBlisExpDt,
            String larName,
            String larIdcard,
            String larIdcardStDt,
            String larIdcardExpDt,
            String larMobile,
            String contactName,
            String contactMobile) {
        @Override
        public String toString() {
            return "MerchantProfileInput[merRegName=" + merRegName + ",redacted]";
        }
    }

    /** Settlement account; the settler trio is null when the settler is the legal person. */
    public record SettlementProfileInput(
            String acctTypeCode,
            String acctName,
            String acctNo,
            String acctIdType,
            String acctIdcard,
            String acctIdExpDt,
            String openningBankCode,
            String openningBankName,
            String clearingBankCode) {
        @Override
        public String toString() {
            return "SettlementProfileInput[acctTypeCode=" + acctTypeCode + ",redacted]";
        }
    }

    /** Operator saves the profile (no Lakala call). {@code attachments}: Lakala attType → platform asset id. */
    public record SaveOnboardingCommand(
            CommandContext context,
            String merchantId,
            long expectedVersion,
            MerchantProfileInput merchant,
            SettlementProfileInput settlement,
            Map<String, String> attachments,
            String cooperationAssetId,
            String reason) {
        public SaveOnboardingCommand {
            attachments = attachments == null ? Map.of() : Map.copyOf(attachments);
        }

        @Override
        public String toString() {
            return "SaveOnboardingCommand[merchantId=" + merchantId + ",expectedVersion=" + expectedVersion
                    + ",redacted]";
        }
    }

    /** Operator starts the chain: electronic contract application first, then the system advances automatically. */
    public record SubmitOnboardingCommand(CommandContext context, String merchantId, long expectedVersion) {}

    /** Operator asks for an immediate channel query of the current waiting step. */
    public record SyncOnboardingCommand(CommandContext context, String merchantId) {}

    /** Operator resubmits the current step after a ledger-open or bind rejection. */
    public record RetryOnboardingCommand(
            CommandContext context,
            String merchantId,
            long expectedVersion,
            String cooperationAssetId,
            String reason) {}

    /** Operator supplies the Lakala 进件 ID after an unknown-outcome submission was confirmed received. */
    public record AdoptContractCommand(
            CommandContext context, String merchantId, long expectedVersion, String contractId, String reason) {}

    /** Operator confirms Lakala never received the unknown-outcome submission; the profile is kept for a restart. */
    public record DiscardContractCommand(
            CommandContext context, String merchantId, long expectedVersion, String reason) {}

    /** Audited operator reveal of plaintext profile fields (reason required). */
    public record RevealOnboardingCommand(CommandContext context, String merchantId, String reason) {}

    public record ListOnboardingsQuery(
            QueryContext context, String merchantId, String status, int page, int size) {}

    // ---- Views ----------------------------------------------------------------------------

    public record FeeRateView(String feeRateTypeCode, String feeRateTypeName, String feeRatePct) {}

    /** Platform-level parameters the deployment provides (Ruling 44 §1); never entered per merchant. */
    public record PlatformDefaultsView(
            String posType,
            String merBusiContent,
            String settlePeriod,
            String mccCode,
            List<FeeRateView> feeRates,
            String splitLowestRatio,
            boolean platformReceiverConfigured) {}

    public record OnboardingDefaultsView(
            String merchantId,
            boolean eligible,
            String ineligibleReason,
            String merchantName,
            String merchantTypeCode,
            String cityCode,
            String districtCode,
            boolean configReady,
            List<String> missingConfig,
            PlatformDefaultsView platform) {}

    public record OnboardingSummaryView(
            String onboardingId,
            String merchantId,
            String status,
            String merCupNo,
            String termNo,
            String rejectReason,
            OffsetDateTime submittedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) {}

    public record MerchantProfileView(
            String merchantTypeCode,
            String merRegName,
            String merBizName,
            String merRegDistCode,
            String merRegAddr,
            String merBlisName,
            String merBlisNoMasked,
            LocalDate merBlisStDt,
            LocalDate merBlisExpDt,
            String larName,
            String larIdType,
            String larIdcardMasked,
            LocalDate larIdcardStDt,
            LocalDate larIdcardExpDt,
            String larMobileMasked,
            String contactName,
            String contactMobileMasked) {}

    public record SettlementProfileView(
            String acctTypeCode,
            String acctName,
            String acctNoMasked,
            String acctNoLast4,
            String acctIdType,
            String acctIdcardMasked,
            LocalDate acctIdExpDt,
            String openningBankCode,
            String openningBankName,
            String clearingBankCode) {}

    /** One timeline step; {@code status} is NONE/WAITING_SIGN/APPLYING/UNKNOWN/REJECTED/DONE. */
    public record OnboardingStepView(
            String step,
            String status,
            OffsetDateTime submittedAt,
            OffsetDateTime finishedAt,
            String summary,
            String reason) {}

    public record OnboardingView(
            String onboardingId,
            String merchantId,
            String status,
            long version,
            MerchantProfileView merchant,
            SettlementProfileView settlement,
            PlatformDefaultsView platform,
            Map<String, String> attachments,
            String cooperationAssetId,
            List<OnboardingStepView> steps,
            String ecSignUrl,
            String ecNo,
            String contractId,
            String merCupNo,
            String termNo,
            String rejectReason,
            String lastErrorCode,
            String lastErrorMessage,
            List<String> allowedActions,
            OffsetDateTime submittedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** Plaintext profile for the audited reveal; never logged, never cached by callers. */
    public record OnboardingSensitiveView(
            String merchantId,
            String merBlisNo,
            String larName,
            String larIdcard,
            String larMobile,
            String contactName,
            String contactMobile,
            String acctName,
            String acctNo,
            String acctIdType,
            String acctIdcard) {
        @Override
        public String toString() {
            return "OnboardingSensitiveView[merchantId=" + merchantId + ",redacted]";
        }
    }

    public record OnboardingAuditView(
            String auditId,
            String actionType,
            String operatorType,
            String operatorId,
            String reason,
            String detailJson,
            OffsetDateTime createdAt) {}

    public record OnboardingPage(List<OnboardingSummaryView> items, long total, int page, int size) {}

    public record OnboardingAssetView(String assetId, String mediaType, long bytes) {}
}
