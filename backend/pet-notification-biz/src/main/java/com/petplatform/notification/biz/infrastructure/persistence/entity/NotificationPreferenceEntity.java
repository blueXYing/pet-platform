package com.petplatform.notification.biz.infrastructure.persistence.entity;

/** Read projection of a notification_preference row (schema 06 section 11). */
public class NotificationPreferenceEntity {
  private Long id;
  private String receiverType;
  private Long receiverId;
  private Boolean interactionEnabled;
  private Boolean externalPushEnabled;
  private Long version;
  private java.time.OffsetDateTime createdAt;
  private java.time.OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getReceiverType() { return receiverType; }
  public void setReceiverType(String receiverType) { this.receiverType = receiverType; }
  public Long getReceiverId() { return receiverId; }
  public void setReceiverId(Long receiverId) { this.receiverId = receiverId; }
  public Boolean getInteractionEnabled() { return interactionEnabled; }
  public void setInteractionEnabled(Boolean interactionEnabled) { this.interactionEnabled = interactionEnabled; }
  public Boolean getExternalPushEnabled() { return externalPushEnabled; }
  public void setExternalPushEnabled(Boolean externalPushEnabled) { this.externalPushEnabled = externalPushEnabled; }
  public Long getVersion() { return version; }
  public void setVersion(Long version) { this.version = version; }
  public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(java.time.OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(java.time.OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
