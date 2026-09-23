package com.petplatform.service.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** service_item full write projection (06号 + 33号 columns); camel case maps snake columns. */
public final class ServiceItemEntity {
    private Long id;
    private Long merchantId;
    private Long storeId;
    private Long categoryId;
    private String serviceName;
    private String description;
    private BigDecimal price;
    private BigDecimal listPrice;
    private Integer durationMinutes;
    private String fulfillmentType;
    private Long coverAssetId;
    private String applicablePetTypes;
    private String staffRequirement;
    private Boolean verificationRequired;
    private String aftersaleNote;
    private String remark;
    private String status;
    private Long submissionNo;
    private Long ownerUserId;
    private LocalDateTime submittedAt;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getListPrice() { return listPrice; }
    public void setListPrice(BigDecimal listPrice) { this.listPrice = listPrice; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getFulfillmentType() { return fulfillmentType; }
    public void setFulfillmentType(String fulfillmentType) { this.fulfillmentType = fulfillmentType; }
    public Long getCoverAssetId() { return coverAssetId; }
    public void setCoverAssetId(Long coverAssetId) { this.coverAssetId = coverAssetId; }
    public String getApplicablePetTypes() { return applicablePetTypes; }
    public void setApplicablePetTypes(String applicablePetTypes) { this.applicablePetTypes = applicablePetTypes; }
    public String getStaffRequirement() { return staffRequirement; }
    public void setStaffRequirement(String staffRequirement) { this.staffRequirement = staffRequirement; }
    public Boolean getVerificationRequired() { return verificationRequired; }
    public void setVerificationRequired(Boolean verificationRequired) { this.verificationRequired = verificationRequired; }
    public String getAftersaleNote() { return aftersaleNote; }
    public void setAftersaleNote(String aftersaleNote) { this.aftersaleNote = aftersaleNote; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSubmissionNo() { return submissionNo; }
    public void setSubmissionNo(Long submissionNo) { this.submissionNo = submissionNo; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
