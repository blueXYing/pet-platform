package com.petplatform.verification.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only contract-63 platform verification statements; SQL lives in
 * AdminVerificationQueryMapper.xml over the module-owned verification_record /
 * verification_attempt / verification_credential_risk_attempt tables. Credential cipher
 * columns are never selected.
 */
public interface AdminVerificationQueryMapper {

    long countVerifications(
            @Param("orderId") Long orderId,
            @Param("storeId") Long storeId,
            @Param("method") String method,
            @Param("verifiedFrom") LocalDateTime verifiedFrom,
            @Param("verifiedTo") LocalDateTime verifiedTo);

    List<VerificationRow> selectVerificationPage(
            @Param("orderId") Long orderId,
            @Param("storeId") Long storeId,
            @Param("method") String method,
            @Param("verifiedFrom") LocalDateTime verifiedFrom,
            @Param("verifiedTo") LocalDateTime verifiedTo,
            @Param("limit") int limit,
            @Param("offset") long offset);

    VerificationRow selectVerificationById(@Param("id") long id);

    /** Full attempt chain of the verified order, oldest first (dispute tracing). */
    List<AttemptRow> selectAttempts(@Param("orderId") long orderId);

    /** Risk attempts (lock decisions) of the verified order, oldest first. */
    List<RiskAttemptRow> selectRiskAttempts(@Param("orderId") long orderId);

    class VerificationRow {
        private Long id;
        private Long verificationNo;
        private Long orderId;
        private Long storeId;
        private Long operatorStaffId;
        private String verifyMethod;
        private LocalDateTime verifiedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getVerificationNo() { return verificationNo; }
        public void setVerificationNo(Long verificationNo) { this.verificationNo = verificationNo; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getOperatorStaffId() { return operatorStaffId; }
        public void setOperatorStaffId(Long operatorStaffId) { this.operatorStaffId = operatorStaffId; }
        public String getVerifyMethod() { return verifyMethod; }
        public void setVerifyMethod(String verifyMethod) { this.verifyMethod = verifyMethod; }
        public LocalDateTime getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    }

    class AttemptRow {
        private Long id;
        private String result;
        private String failReason;
        private LocalDateTime attemptedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public String getFailReason() { return failReason; }
        public void setFailReason(String failReason) { this.failReason = failReason; }
        public LocalDateTime getAttemptedAt() { return attemptedAt; }
        public void setAttemptedAt(LocalDateTime attemptedAt) { this.attemptedAt = attemptedAt; }
    }

    class RiskAttemptRow {
        private Long id;
        private String resultCode;
        private Boolean countsFailure;
        private LocalDateTime attemptedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getResultCode() { return resultCode; }
        public void setResultCode(String resultCode) { this.resultCode = resultCode; }
        public Boolean getCountsFailure() { return countsFailure; }
        public void setCountsFailure(Boolean countsFailure) { this.countsFailure = countsFailure; }
        public boolean isCountsFailure() { return countsFailure != null && countsFailure; }
        public LocalDateTime getAttemptedAt() { return attemptedAt; }
        public void setAttemptedAt(LocalDateTime attemptedAt) { this.attemptedAt = attemptedAt; }
    }
}
