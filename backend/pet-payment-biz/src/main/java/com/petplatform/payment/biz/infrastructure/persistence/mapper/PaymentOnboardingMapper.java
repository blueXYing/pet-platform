package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** MyBatis XML mapper for Contract 81 / V44 merchant acquiring onboarding. Zero SQL annotations. */
public interface PaymentOnboardingMapper {
    OnboardingRow selectByMerchantId(long merchantId);
    OnboardingRow selectByMerchantIdForUpdate(long merchantId);
    OnboardingRow selectByContractId(String contractId);
    OnboardingRow selectByMerCupNo(String merCupNo);
    OnboardingRow selectByEcApplyId(String ecApplyId);
    OnboardingRow selectByEcOrderNo(String ecOrderNo);
    OnboardingRow selectByLedgerApplyId(String applyId);
    OnboardingRow selectByBindApplyId(String applyId);
    List<OnboardingRow> listOnboardings(Map<String, Object> params);
    long countOnboardings(Map<String, Object> params);
    int insertOnboarding(Map<String, Object> params);
    int updateOnboarding(Map<String, Object> params);

    OnboardingAuditRow selectAuditByRequestKeyForUpdate(byte[] requestKey);
    int insertAudit(Map<String, Object> params);
    List<OnboardingAuditRow> listAuditsByMerchant(Map<String, Object> params);

    /** One merchant's acquiring onboarding record; every column is mapped, the service always writes whole rows. */
    final class OnboardingRow {
        public long id;
        public long merchantId;
        public String status;
        public String merchantTypeCode;
        public String merRegName;
        public String merBizName;
        public String merRegDistCode;
        public String merRegAddr;
        public String merBlisName;
        public String merBlisNoCipher;
        public String merBlisNoMasked;
        public LocalDate merBlisStDt;
        public LocalDate merBlisExpDt;
        public String larName;
        public String larIdType;
        public String larIdcardCipher;
        public String larIdcardMasked;
        public LocalDate larIdcardStDt;
        public LocalDate larIdcardExpDt;
        public String larMobileCipher;
        public String larMobileMasked;
        public String contactName;
        public String contactMobileCipher;
        public String contactMobileMasked;
        public String acctTypeCode;
        public String acctName;
        public String acctNoCipher;
        public String acctNoMasked;
        public String acctNoLast4;
        public String acctIdType;
        public String acctIdcardCipher;
        public String acctIdcardMasked;
        public LocalDate acctIdExpDt;
        public String openningBankCode;
        public String openningBankName;
        public String clearingBankCode;
        public String posType;
        public String mccCode;
        public String merBusiContent;
        public String settlePeriod;
        public String feeDataJson;
        public BigDecimal splitLowestRatio;
        public String attachmentsJson;
        public String cooperationAssetId;
        public String ecOrderNo;
        public String ecApplyId;
        public String ecSignUrl;
        public String ecNo;
        public LocalDateTime ecSignedAt;
        public String contractOrderNo;
        public String contractId;
        public String contractStatus;
        public LocalDateTime contractSubmittedAt;
        public LocalDateTime contractDecidedAt;
        public String merInnerNo;
        public String merCupNo;
        public String termNo;
        public String ledgerOrderNo;
        public String ledgerApplyId;
        public LocalDateTime ledgerSubmittedAt;
        public LocalDateTime ledgerOpenedAt;
        public String bindOrderNo;
        public String bindApplyId;
        public LocalDateTime bindSubmittedAt;
        public LocalDateTime bindApprovedAt;
        public String rejectReason;
        public String lastErrorCode;
        public String lastErrorMessage;
        public LocalDateTime submittedAt;
        public LocalDateTime completedAt;
        public long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    final class OnboardingAuditRow {
        public long id;
        public long merchantId;
        public long onboardingId;
        public String actionType;
        public byte[] requestKey;
        public String requestFingerprint;
        public String operatorType;
        public Long operatorId;
        public String reason;
        public String detailJson;
        public LocalDateTime createdAt;
    }
}
