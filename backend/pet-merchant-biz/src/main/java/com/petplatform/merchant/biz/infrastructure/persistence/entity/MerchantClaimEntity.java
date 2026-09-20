package com.petplatform.merchant.biz.infrastructure.persistence.entity;

public class MerchantClaimEntity {
  private Long id, evidenceId;
  private String claimType, lookupKeyVersion, status;
  private byte[] lookupDigest;
  private Integer lookupPolicySlot;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getEvidenceId() {
    return evidenceId;
  }

  public void setEvidenceId(Long v) {
    evidenceId = v;
  }

  public String getClaimType() {
    return claimType;
  }

  public void setClaimType(String v) {
    claimType = v;
  }

  public String getLookupKeyVersion() {
    return lookupKeyVersion;
  }

  public void setLookupKeyVersion(String v) {
    lookupKeyVersion = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public byte[] getLookupDigest() {
    return lookupDigest;
  }

  public void setLookupDigest(byte[] v) {
    lookupDigest = v;
  }

  public Integer getLookupPolicySlot() {
    return lookupPolicySlot;
  }

  public void setLookupPolicySlot(Integer v) {
    lookupPolicySlot = v;
  }
}
