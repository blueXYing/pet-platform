package com.petplatform.thirdparty.biz.infrastructure.persistence.entity;

public class PrivateAssetUploadBindingEntity {
  private long assetId;
  private byte[] requestHash;

  public long getAssetId() {
    return assetId;
  }

  public void setAssetId(long assetId) {
    this.assetId = assetId;
  }

  public byte[] getRequestHash() {
    return requestHash;
  }

  public void setRequestHash(byte[] requestHash) {
    this.requestHash = requestHash;
  }
}
