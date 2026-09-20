package com.petplatform.thirdparty.biz.infrastructure.persistence.entity;

public class PrivateAssetEntity {
  private long id;
  private long ownerUserId;
  private String purpose;
  private String sourceObjectKey;
  private String sourceObjectVersionRef;
  private String objectKey;
  private String objectVersionRef;
  private String sourceSha256;
  private String objectSha256;
  private String mediaType;
  private Long bytes;
  private String status;
  private long version;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public long getOwnerUserId() {
    return ownerUserId;
  }

  public void setOwnerUserId(long ownerUserId) {
    this.ownerUserId = ownerUserId;
  }

  public String getPurpose() {
    return purpose;
  }

  public void setPurpose(String purpose) {
    this.purpose = purpose;
  }

  public String getSourceObjectKey() {
    return sourceObjectKey;
  }

  public void setSourceObjectKey(String value) {
    this.sourceObjectKey = value;
  }

  public String getSourceObjectVersionRef() {
    return sourceObjectVersionRef;
  }

  public void setSourceObjectVersionRef(String value) {
    this.sourceObjectVersionRef = value;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public void setObjectKey(String value) {
    this.objectKey = value;
  }

  public String getObjectVersionRef() {
    return objectVersionRef;
  }

  public void setObjectVersionRef(String value) {
    this.objectVersionRef = value;
  }

  public String getSourceSha256() {
    return sourceSha256;
  }

  public void setSourceSha256(String value) {
    this.sourceSha256 = value;
  }

  public String getObjectSha256() {
    return objectSha256;
  }

  public void setObjectSha256(String value) {
    this.objectSha256 = value;
  }

  public String getMediaType() {
    return mediaType;
  }

  public void setMediaType(String value) {
    this.mediaType = value;
  }

  public Long getBytes() {
    return bytes;
  }

  public void setBytes(Long bytes) {
    this.bytes = bytes;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }
}
