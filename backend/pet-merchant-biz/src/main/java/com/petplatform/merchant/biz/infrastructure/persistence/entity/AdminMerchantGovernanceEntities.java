package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only rows behind the contract-64 merchant governance face (schema 06/28/29/52/54). */
public final class AdminMerchantGovernanceEntities {
    private AdminMerchantGovernanceEntities() {}

    /** Ledger projection: merchant + profile compat + reserved application + current signing. */
    public static final class AdminMerchantLedgerRow {
        private Long id;
        private String merchantName;
        private String status;
        private String providerMerchantNo;
        private String merchantTypeCode;
        private String cityCode;
        private String applicationStatus;
        private Long applicationId;
        private String applicationNo;
        private LocalDateTime applicationSubmittedAt;
        private LocalDateTime applicationReviewedAt;
        private LocalDateTime signingAcceptedAt;
        private String agreementVersion;
        private int storeCount;
        private int activeStoreCount;
        private LocalDateTime createdAt;
        private Long version;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getProviderMerchantNo() { return providerMerchantNo; }
        public void setProviderMerchantNo(String providerMerchantNo) { this.providerMerchantNo = providerMerchantNo; }
        public String getMerchantTypeCode() { return merchantTypeCode; }
        public void setMerchantTypeCode(String merchantTypeCode) { this.merchantTypeCode = merchantTypeCode; }
        public String getCityCode() { return cityCode; }
        public void setCityCode(String cityCode) { this.cityCode = cityCode; }
        public String getApplicationStatus() { return applicationStatus; }
        public void setApplicationStatus(String applicationStatus) { this.applicationStatus = applicationStatus; }
        public Long getApplicationId() { return applicationId; }
        public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
        public String getApplicationNo() { return applicationNo; }
        public void setApplicationNo(String applicationNo) { this.applicationNo = applicationNo; }
        public LocalDateTime getApplicationSubmittedAt() { return applicationSubmittedAt; }
        public void setApplicationSubmittedAt(LocalDateTime applicationSubmittedAt) { this.applicationSubmittedAt = applicationSubmittedAt; }
        public LocalDateTime getApplicationReviewedAt() { return applicationReviewedAt; }
        public void setApplicationReviewedAt(LocalDateTime applicationReviewedAt) { this.applicationReviewedAt = applicationReviewedAt; }
        public LocalDateTime getSigningAcceptedAt() { return signingAcceptedAt; }
        public void setSigningAcceptedAt(LocalDateTime signingAcceptedAt) { this.signingAcceptedAt = signingAcceptedAt; }
        public String getAgreementVersion() { return agreementVersion; }
        public void setAgreementVersion(String agreementVersion) { this.agreementVersion = agreementVersion; }
        public int getStoreCount() { return storeCount; }
        public void setStoreCount(int storeCount) { this.storeCount = storeCount; }
        public int getActiveStoreCount() { return activeStoreCount; }
        public void setActiveStoreCount(int activeStoreCount) { this.activeStoreCount = activeStoreCount; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
    }

    public static final class AdminStoreRow {
        private Long id;
        private String storeName;
        private String address;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getStoreName() { return storeName; }
        public void setStoreName(String storeName) { this.storeName = storeName; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /** merchant_staff row; phone is masked inside the service before any projection. */
    public static final class AdminStaffRow {
        private Long id;
        private Long storeId;
        private String staffName;
        private String phone;
        private String employmentStatus;
        private Boolean serviceEnabled;
        private Long version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public String getStaffName() { return staffName; }
        public void setStaffName(String staffName) { this.staffName = staffName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getEmploymentStatus() { return employmentStatus; }
        public void setEmploymentStatus(String employmentStatus) { this.employmentStatus = employmentStatus; }
        public Boolean getServiceEnabled() { return serviceEnabled; }
        public void setServiceEnabled(Boolean serviceEnabled) { this.serviceEnabled = serviceEnabled; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    /** member + grant + CONFIRMED-invitation projection; grant facts null when grant absent. */
    public static final class AdminMemberRow {
        private Long memberId;
        private Long storeId;
        private String memberStatus;
        private Long memberVersion;
        private String grantStatus;
        private Long grantVersion;
        private String memberName;
        private String phone;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<String> grantedActions;

        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public String getMemberStatus() { return memberStatus; }
        public void setMemberStatus(String memberStatus) { this.memberStatus = memberStatus; }
        public Long getMemberVersion() { return memberVersion; }
        public void setMemberVersion(Long memberVersion) { this.memberVersion = memberVersion; }
        public String getGrantStatus() { return grantStatus; }
        public void setGrantStatus(String grantStatus) { this.grantStatus = grantStatus; }
        public Long getGrantVersion() { return grantVersion; }
        public void setGrantVersion(Long grantVersion) { this.grantVersion = grantVersion; }
        public String getMemberName() { return memberName; }
        public void setMemberName(String memberName) { this.memberName = memberName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public List<String> getGrantedActions() { return grantedActions; }
        public void setGrantedActions(List<String> grantedActions) { this.grantedActions = grantedActions; }
    }

    public static final class AdminInvitationRow {
        private Long id;
        private Long storeId;
        private String memberName;
        private String phone;
        private String status;
        private Long invitedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<String> grantedActions;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public String getMemberName() { return memberName; }
        public void setMemberName(String memberName) { this.memberName = memberName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getInvitedBy() { return invitedBy; }
        public void setInvitedBy(Long invitedBy) { this.invitedBy = invitedBy; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public List<String> getGrantedActions() { return grantedActions; }
        public void setGrantedActions(List<String> grantedActions) { this.grantedActions = grantedActions; }
    }

    /** (ownerId, actionCode) pair for batch action lookup (member or invitation ids). */
    public static final class AdminActionRefRow {
        private Long ownerId;
        private String actionCode;

        public Long getOwnerId() { return ownerId; }
        public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
        public String getActionCode() { return actionCode; }
        public void setActionCode(String actionCode) { this.actionCode = actionCode; }
    }
}
