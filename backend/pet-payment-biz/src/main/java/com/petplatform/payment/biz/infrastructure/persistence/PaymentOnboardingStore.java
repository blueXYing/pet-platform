package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentFoundationMapper;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentOnboardingMapper;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentOnboardingMapper.OnboardingRow;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.BadSqlGrammarException;

/** PAYMENT-owned merchant acquiring onboarding persistence (Contract 81 / V44), sharing the caller's transaction. */
public final class PaymentOnboardingStore {
    /** MySQL error code and SQL state of a table that does not exist (yet). */
    private static final int TABLE_MISSING = 1146;
    private static final String TABLE_MISSING_STATE = "42S02";

    public final PaymentOnboardingMapper onboarding;
    private final PaymentFoundationMapper shared;

    public PaymentOnboardingStore(DataSource source) {
        SqlSessionTemplate template = PaymentMybatis.template(source);
        this.onboarding = template.getMapper(PaymentOnboardingMapper.class);
        this.shared = template.getMapper(PaymentFoundationMapper.class);
    }

    /**
     * The merchant's onboarding row, or {@code null} when there is none. A ledger table that does not exist
     * (V44 not applied yet) cannot hold a record either, so it reads as "none" for the callers that only
     * guard pre-existing behaviour — payments and settlement configuration keep working until the schema
     * catches up. Every other failure is rethrown: a broken database must still fail closed.
     */
    public OnboardingRow findIfApplied(long merchantId) {
        try {
            return onboarding.selectByMerchantId(merchantId);
        } catch (BadSqlGrammarException failure) {
            if (failure.getSQLException() != null
                    && (failure.getSQLException().getErrorCode() == TABLE_MISSING
                            || TABLE_MISSING_STATE.equals(failure.getSQLException().getSQLState()))) {
                return null;
            }
            throw failure;
        }
    }

    public void session() {
        shared.setUtcTimeZone();
        shared.setLockWaitTimeout();
    }

    public OffsetDateTime now() {
        return shared.utcNow().atOffset(ZoneOffset.UTC);
    }

    public static LocalDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Named bind values for the whole row; {@code updated_at} is stamped, {@code created_at} only on insert. */
    public static Map<String, Object> values(OnboardingRow row, LocalDateTime now) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", row.id);
        values.put("merchantId", row.merchantId);
        values.put("status", row.status);
        values.put("merchantTypeCode", row.merchantTypeCode);
        values.put("merRegName", row.merRegName);
        values.put("merBizName", row.merBizName);
        values.put("merRegDistCode", row.merRegDistCode);
        values.put("merRegAddr", row.merRegAddr);
        values.put("merBlisName", row.merBlisName);
        values.put("merBlisNoCipher", row.merBlisNoCipher);
        values.put("merBlisNoMasked", row.merBlisNoMasked);
        values.put("merBlisStDt", row.merBlisStDt);
        values.put("merBlisExpDt", row.merBlisExpDt);
        values.put("larName", row.larName);
        values.put("larIdType", row.larIdType);
        values.put("larIdcardCipher", row.larIdcardCipher);
        values.put("larIdcardMasked", row.larIdcardMasked);
        values.put("larIdcardStDt", row.larIdcardStDt);
        values.put("larIdcardExpDt", row.larIdcardExpDt);
        values.put("larMobileCipher", row.larMobileCipher);
        values.put("larMobileMasked", row.larMobileMasked);
        values.put("contactName", row.contactName);
        values.put("contactMobileCipher", row.contactMobileCipher);
        values.put("contactMobileMasked", row.contactMobileMasked);
        values.put("acctTypeCode", row.acctTypeCode);
        values.put("acctName", row.acctName);
        values.put("acctNoCipher", row.acctNoCipher);
        values.put("acctNoMasked", row.acctNoMasked);
        values.put("acctNoLast4", row.acctNoLast4);
        values.put("acctIdType", row.acctIdType);
        values.put("acctIdcardCipher", row.acctIdcardCipher);
        values.put("acctIdcardMasked", row.acctIdcardMasked);
        values.put("acctIdExpDt", row.acctIdExpDt);
        values.put("openningBankCode", row.openningBankCode);
        values.put("openningBankName", row.openningBankName);
        values.put("clearingBankCode", row.clearingBankCode);
        values.put("posType", row.posType);
        values.put("mccCode", row.mccCode);
        values.put("merBusiContent", row.merBusiContent);
        values.put("settlePeriod", row.settlePeriod);
        values.put("feeDataJson", row.feeDataJson);
        values.put("splitLowestRatio", row.splitLowestRatio);
        values.put("attachmentsJson", row.attachmentsJson);
        values.put("cooperationAssetId", row.cooperationAssetId);
        values.put("ecOrderNo", row.ecOrderNo);
        values.put("ecApplyId", row.ecApplyId);
        values.put("ecSignUrl", row.ecSignUrl);
        values.put("ecNo", row.ecNo);
        values.put("ecSignedAt", row.ecSignedAt);
        values.put("contractOrderNo", row.contractOrderNo);
        values.put("contractId", row.contractId);
        values.put("contractStatus", row.contractStatus);
        values.put("contractSubmittedAt", row.contractSubmittedAt);
        values.put("contractDecidedAt", row.contractDecidedAt);
        values.put("merInnerNo", row.merInnerNo);
        values.put("merCupNo", row.merCupNo);
        values.put("termNo", row.termNo);
        values.put("ledgerOrderNo", row.ledgerOrderNo);
        values.put("ledgerApplyId", row.ledgerApplyId);
        values.put("ledgerSubmittedAt", row.ledgerSubmittedAt);
        values.put("ledgerOpenedAt", row.ledgerOpenedAt);
        values.put("bindOrderNo", row.bindOrderNo);
        values.put("bindApplyId", row.bindApplyId);
        values.put("bindSubmittedAt", row.bindSubmittedAt);
        values.put("bindApprovedAt", row.bindApprovedAt);
        values.put("rejectReason", row.rejectReason);
        values.put("lastErrorCode", row.lastErrorCode);
        values.put("lastErrorMessage", row.lastErrorMessage);
        values.put("submittedAt", row.submittedAt);
        values.put("completedAt", row.completedAt);
        values.put("version", row.version);
        values.put("createdAt", row.createdAt == null ? now : row.createdAt);
        values.put("updatedAt", now);
        return values;
    }

    public void insert(OnboardingRow row, LocalDateTime now) {
        onboarding.insertOnboarding(values(row, now));
    }

    /** Whole-row optimistic update; returns {@code false} when the row's version has moved. */
    public boolean update(OnboardingRow row, LocalDateTime now) {
        if (onboarding.updateOnboarding(values(row, now)) != 1) return false;
        row.version++;
        row.updatedAt = now;
        return true;
    }
}
