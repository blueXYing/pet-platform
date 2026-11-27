package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

/** MyBatis XML mapper for the acquiring-admission facts of one merchant (CCR-PAY-ONBOARD-001). Zero SQL annotations. */
public interface MerchantAcquiringFactsMapper {
  MerchantAcquiringFactRow selectFact(long merchantId);

  final class MerchantAcquiringFactRow {
    private Long id;
    private Long ownerUserId;
    private String merchantName;
    private String status;
    private String merchantTypeCode;
    private String cityCode;
    private String applicationStatus;
    private String signingStatus;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getMerchantName() { return merchantName; }
    public String getStatus() { return status; }
    public String getMerchantTypeCode() { return merchantTypeCode; }
    public String getCityCode() { return cityCode; }
    public String getApplicationStatus() { return applicationStatus; }
    public String getSigningStatus() { return signingStatus; }
    public void setId(Long id) { this.id = id; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public void setStatus(String status) { this.status = status; }
    public void setMerchantTypeCode(String merchantTypeCode) { this.merchantTypeCode = merchantTypeCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public void setApplicationStatus(String applicationStatus) { this.applicationStatus = applicationStatus; }
    public void setSigningStatus(String signingStatus) { this.signingStatus = signingStatus; }
  }
}
