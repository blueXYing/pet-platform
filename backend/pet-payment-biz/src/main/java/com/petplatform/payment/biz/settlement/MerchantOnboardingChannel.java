package com.petplatform.payment.biz.settlement;

import java.math.BigDecimal;

/**
 * Channel port for merchant acquiring onboarding (CCR-PAY-ONBOARD-001 §5). Implementations talk to
 * Lakala; the state machine only sees verified outcomes classified as success, a deterministic
 * business rejection, or an unknown outcome the caller must not blindly resubmit.
 */
public interface MerchantOnboardingChannel {

    /** 1084 attachment upload; the returned {@code attFileId} is valid for 24 hours and is never persisted. */
    Uploaded uploadAttachment(String orderNo, String orgCode, String attType, String extName, byte[] content);

    /** 1208 electronic contract application for the 清分结算授权委托书 (EC011). */
    EcApplied applyElectronicContract(EcApplication application);

    /** 1212 signing status query. */
    EcProgress queryElectronicContract(String orderNo, String orgId, String ecApplyId);

    /** 1020 新增商户进件. */
    ContractAdded addMerchant(ContractApplication application);

    /** 1082 进件信息查询. */
    ContractProgress queryContract(String orderNo, String orgCode, String contractId);

    /** 1284 商户分账业务开通申请. */
    LedgerApplied applyLedger(LedgerApplication application);

    /** 1286 商户分账信息查询. */
    LedgerProgress queryLedger(String orderNo, String orgCode, String merCupNo);

    /** 1290 分账关系绑定申请 between the merchant's acquiring number and the platform receiver. */
    BindApplied applyBind(BindApplication application);

    /** 1872 relation-apply lookup for one merchant/receiver pair. */
    BindProgress queryBind(String orderNo, String orgCode, String merCupNo, String receiverNo, String applyId);

    // ---- Outcomes --------------------------------------------------------------------------

    record Uploaded(String attType, String attFileId) {}

    record EcApplied(String ecApplyId, String resultUrl) {}

    /** {@code completed} is true only with a non-blank {@code ecNo}. */
    record EcProgress(boolean completed, String ecNo) {}

    record ContractAdded(String contractId) {}

    /** Lakala's view of one 进件; unknown statuses are neither approved nor rejected (still pending). */
    record ContractProgress(String contractId, String contractStatus, String contractMemo,
            String merInnerNo, String merCupNo, String termNo) {
        public boolean approved() {
            return "WAIT_FOR_CONTACT".equals(contractStatus) && merCupNo != null && termNo != null;
        }

        public boolean rejected() {
            return "COMMIT_FAIL".equals(contractStatus) || "INNER_CHECK_REJECTED".equals(contractStatus);
        }
    }

    record LedgerApplied(String applyId) {}

    /** {@code valid} means split business is enabled for the merchant at Lakala. */
    record LedgerProgress(boolean valid) {}

    record BindApplied(String applyId) {}

    /** {@code decided} is false while Lakala's review is still pending. */
    record BindProgress(boolean decided, boolean approved, String remark) {}

    // ---- Requests --------------------------------------------------------------------------

    record EcApplication(
            String orderNo,
            String orgId,
            String legalName,
            String legalIdCard,
            String legalMobile,
            String licenseNo,
            String licenseName,
            String openningBankCode,
            String openningBankName,
            String acctTypeCode,
            String acctNo,
            String acctName,
            String partnerName,
            String cooperationAgreementName,
            String merchantLowestRatio,
            String platformName,
            String signDate,
            String retUrl) {
        @Override
        public String toString() {
            return "EcApplication[orderNo=" + orderNo + ",redacted]";
        }
    }

    record FeeRate(String feeRateTypeCode, String feeRateTypeName, String feeRatePct) {}

    record ContractApplication(
            String orderNo,
            String posType,
            String orgCode,
            String merRegName,
            String merBizName,
            String merRegDistCode,
            String merRegAddr,
            String mccCode,
            String merBlisName,
            String merBlis,
            String merBlisStDt,
            String merBlisExpDt,
            String merBusiContent,
            String larName,
            String larIdcard,
            String larIdcardStDt,
            String larIdcardExpDt,
            String merContactMobile,
            String merContactName,
            String openningBankCode,
            String openningBankName,
            String clearingBankCode,
            String acctNo,
            String acctName,
            String acctTypeCode,
            String acctIdcard,
            String acctIdDt,
            String settlePeriod,
            String retUrl,
            String contractNo,
            java.util.List<FeeRate> feeData,
            java.util.List<Uploaded> fileData) {
        @Override
        public String toString() {
            return "ContractApplication[orderNo=" + orderNo + ",redacted]";
        }
    }

    record LedgerApplication(String orderNo, String orgCode, String merCupNo, String contactMobile,
            BigDecimal splitLowestRatio, String eleContractNo, String retUrl) {
        @Override
        public String toString() {
            return "LedgerApplication[orderNo=" + orderNo + ",merCupNo=" + merCupNo + "]";
        }
    }

    /** {@code entrustFile} is the already uploaded cooperation agreement (1290 requires it). */
    record BindApplication(String orderNo, String orgCode, String merCupNo, String receiverNo,
            String entrustFileName, Uploaded entrustFile, String retUrl) {}

    /** Deterministic business rejection returned by a verified channel response. */
    final class Rejected extends RuntimeException {
        private final String code;

        public Rejected(String code, String message) {
            super(message == null || message.isBlank() ? "渠道拒绝：" + code : message);
            this.code = code == null ? "UNKNOWN" : code;
        }

        public String code() {
            return code;
        }
    }

    /** Outcome unknown (transport failure, unverifiable or malformed response): never resubmit blindly. */
    final class Unknown extends RuntimeException {
        public Unknown(String reason, Throwable cause) {
            super("渠道结果未知：" + reason, cause);
        }
    }
}
