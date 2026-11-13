package com.petplatform.verification.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only merchant verification-record statements (CCR-M-VERIFY-RECORD-001); SQL lives in
 * MerchantVerificationReadMapper.xml over the module-owned verification_record table (schema
 * 06 + 48). Only the store-scoped list columns are selected: the raw operator_id never crosses
 * this face (CCR §4) and no credential cipher column is touched.
 */
public interface MerchantVerificationReadMapper {

    long countStoreVerifications(
            @Param("storeId") long storeId,
            @Param("orderId") Long orderId,
            @Param("verifiedFrom") LocalDateTime verifiedFrom,
            @Param("verifiedTo") LocalDateTime verifiedTo);

    List<VerificationRow> selectStoreVerificationPage(
            @Param("storeId") long storeId,
            @Param("orderId") Long orderId,
            @Param("verifiedFrom") LocalDateTime verifiedFrom,
            @Param("verifiedTo") LocalDateTime verifiedTo,
            @Param("limit") int limit,
            @Param("offset") long offset);

    class VerificationRow {
        private Long id;
        private Long orderId;
        private Long storeId;
        private String operatorType;
        private String membershipKind;
        private Long operatorStaffId;
        private String verifyMethod;
        private LocalDateTime verifiedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public String getOperatorType() { return operatorType; }
        public void setOperatorType(String operatorType) { this.operatorType = operatorType; }
        public String getMembershipKind() { return membershipKind; }
        public void setMembershipKind(String membershipKind) { this.membershipKind = membershipKind; }
        public Long getOperatorStaffId() { return operatorStaffId; }
        public void setOperatorStaffId(Long operatorStaffId) { this.operatorStaffId = operatorStaffId; }
        public String getVerifyMethod() { return verifyMethod; }
        public void setVerifyMethod(String verifyMethod) { this.verifyMethod = verifyMethod; }
        public LocalDateTime getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    }
}
