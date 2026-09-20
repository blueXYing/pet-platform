package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDate;

public class MerchantEvidenceEntity {
  private Long id, materialId;
  private String credentialType, materialType, evidenceStatus, lookupKeyVersion;
  private byte[] identifierLookupDigest, subjectNameProtected;
  private Integer lookupPolicySlot;
  private LocalDate validFrom, validTo;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getMaterialId() {
    return materialId;
  }

  public void setMaterialId(Long v) {
    materialId = v;
  }

  public String getCredentialType() {
    return credentialType;
  }

  public void setCredentialType(String v) {
    credentialType = v;
  }

  public String getMaterialType() {
    return materialType;
  }

  public void setMaterialType(String v) {
    materialType = v;
  }

  public String getEvidenceStatus() {
    return evidenceStatus;
  }

  public void setEvidenceStatus(String v) {
    evidenceStatus = v;
  }

  public String getLookupKeyVersion() {
    return lookupKeyVersion;
  }

  public void setLookupKeyVersion(String v) {
    lookupKeyVersion = v;
  }

  public byte[] getIdentifierLookupDigest() {
    return identifierLookupDigest;
  }

  public void setIdentifierLookupDigest(byte[] v) {
    identifierLookupDigest = v;
  }

  public Integer getLookupPolicySlot() {
    return lookupPolicySlot;
  }

  public void setLookupPolicySlot(Integer v) {
    lookupPolicySlot = v;
  }

  public LocalDate getValidFrom() {
    return validFrom;
  }

  public void setValidFrom(LocalDate v) {
    validFrom = v;
  }

  public LocalDate getValidTo() {
    return validTo;
  }

  public void setValidTo(LocalDate v) {
    validTo = v;
  }

  public byte[] getSubjectNameProtected() {
    return subjectNameProtected;
  }

  public void setSubjectNameProtected(byte[] v) {
    subjectNameProtected = v;
  }
}
