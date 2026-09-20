package com.petplatform.thirdparty.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

public class PrivateAssetGrantEntity {
  private long id;
  private byte[] tokenDigest;
  private byte[] tokenProof;
  private String tokenKeyVersion;
  private long assetId;
  private long applicationId;
  private long revisionId;
  private long materialId;
  private String materialType;
  private String objectSha256;
  private String operatorId;
  private byte[] sessionIdDigest;
  private long sessionGeneration;
  private String purposeCode;
  private String requestId;
  private byte[] requestHash;
  private String authzVersion;
  private String scopeVersion;
  private String status;
  private LocalDateTime expiresAt;
  private boolean expired;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public byte[] getTokenDigest() {
    return tokenDigest;
  }

  public void setTokenDigest(byte[] value) {
    this.tokenDigest = value;
  }

  public byte[] getTokenProof() {
    return tokenProof;
  }

  public void setTokenProof(byte[] value) {
    this.tokenProof = value;
  }

  public String getTokenKeyVersion() {
    return tokenKeyVersion;
  }

  public void setTokenKeyVersion(String value) {
    this.tokenKeyVersion = value;
  }

  public long getAssetId() {
    return assetId;
  }

  public void setAssetId(long value) {
    this.assetId = value;
  }

  public long getApplicationId() {
    return applicationId;
  }

  public void setApplicationId(long value) {
    this.applicationId = value;
  }

  public long getRevisionId() {
    return revisionId;
  }

  public void setRevisionId(long value) {
    this.revisionId = value;
  }

  public long getMaterialId() {
    return materialId;
  }

  public void setMaterialId(long value) {
    this.materialId = value;
  }

  public String getMaterialType() {
    return materialType;
  }

  public void setMaterialType(String value) {
    this.materialType = value;
  }

  public String getObjectSha256() {
    return objectSha256;
  }

  public void setObjectSha256(String value) {
    this.objectSha256 = value;
  }

  public String getOperatorId() {
    return operatorId;
  }

  public void setOperatorId(String value) {
    this.operatorId = value;
  }

  public byte[] getSessionIdDigest() {
    return sessionIdDigest;
  }

  public void setSessionIdDigest(byte[] value) {
    this.sessionIdDigest = value;
  }

  public long getSessionGeneration() {
    return sessionGeneration;
  }

  public void setSessionGeneration(long value) {
    this.sessionGeneration = value;
  }

  public String getPurposeCode() {
    return purposeCode;
  }

  public void setPurposeCode(String value) {
    this.purposeCode = value;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String value) {
    this.requestId = value;
  }

  public byte[] getRequestHash() {
    return requestHash;
  }

  public void setRequestHash(byte[] value) {
    this.requestHash = value;
  }

  public String getAuthzVersion() {
    return authzVersion;
  }

  public void setAuthzVersion(String value) {
    this.authzVersion = value;
  }

  public String getScopeVersion() {
    return scopeVersion;
  }

  public void setScopeVersion(String value) {
    this.scopeVersion = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime value) {
    this.expiresAt = value;
  }

  public boolean isExpired() {
    return expired;
  }

  public void setExpired(boolean value) {
    this.expired = value;
  }
}
