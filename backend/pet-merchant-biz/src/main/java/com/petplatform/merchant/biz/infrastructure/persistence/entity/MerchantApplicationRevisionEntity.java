package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class MerchantApplicationRevisionEntity {
  private Long id, applicationId, createdByUserId;
  private Integer revisionNo;
  private String merchantName,
      contactName,
      merchantTypeCode,
      cityCode,
      address,
      introduction,
      canonicalSha256;
  private byte[] contactPhoneProtected, emailProtected;
  private BigDecimal longitude, latitude;
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getApplicationId() {
    return applicationId;
  }

  public void setApplicationId(Long v) {
    applicationId = v;
  }

  public Long getCreatedByUserId() {
    return createdByUserId;
  }

  public void setCreatedByUserId(Long v) {
    createdByUserId = v;
  }

  public Integer getRevisionNo() {
    return revisionNo;
  }

  public void setRevisionNo(Integer v) {
    revisionNo = v;
  }

  public String getMerchantName() {
    return merchantName;
  }

  public void setMerchantName(String v) {
    merchantName = v;
  }

  public String getContactName() {
    return contactName;
  }

  public void setContactName(String v) {
    contactName = v;
  }

  public String getMerchantTypeCode() {
    return merchantTypeCode;
  }

  public void setMerchantTypeCode(String v) {
    merchantTypeCode = v;
  }

  public String getCityCode() {
    return cityCode;
  }

  public void setCityCode(String v) {
    cityCode = v;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String v) {
    address = v;
  }

  public String getIntroduction() {
    return introduction;
  }

  public void setIntroduction(String v) {
    introduction = v;
  }

  public String getCanonicalSha256() {
    return canonicalSha256;
  }

  public void setCanonicalSha256(String v) {
    canonicalSha256 = v;
  }

  public byte[] getContactPhoneProtected() {
    return contactPhoneProtected;
  }

  public void setContactPhoneProtected(byte[] v) {
    contactPhoneProtected = v;
  }

  public byte[] getEmailProtected() {
    return emailProtected;
  }

  public void setEmailProtected(byte[] v) {
    emailProtected = v;
  }

  public BigDecimal getLongitude() {
    return longitude;
  }

  public void setLongitude(BigDecimal v) {
    longitude = v;
  }

  public BigDecimal getLatitude() {
    return latitude;
  }

  public void setLatitude(BigDecimal v) {
    latitude = v;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime v) {
    createdAt = v;
  }
}
