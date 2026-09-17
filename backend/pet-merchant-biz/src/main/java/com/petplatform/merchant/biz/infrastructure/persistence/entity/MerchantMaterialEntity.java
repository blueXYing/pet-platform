package com.petplatform.merchant.biz.infrastructure.persistence.entity;

public class MerchantMaterialEntity {
  private Long id, privateAssetId;
  private String materialType, sha256, mediaType;
  private Integer position, bytes;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getPrivateAssetId() {
    return privateAssetId;
  }

  public void setPrivateAssetId(Long v) {
    privateAssetId = v;
  }

  public String getMaterialType() {
    return materialType;
  }

  public void setMaterialType(String v) {
    materialType = v;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String v) {
    sha256 = v;
  }

  public String getMediaType() {
    return mediaType;
  }

  public void setMediaType(String v) {
    mediaType = v;
  }

  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer v) {
    position = v;
  }

  public Integer getBytes() {
    return bytes;
  }

  public void setBytes(Integer v) {
    bytes = v;
  }
}
