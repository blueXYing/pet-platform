package com.petplatform.notification.biz.infrastructure.persistence.entity;

/** Read projection of one notification_delivery row (Schema 06 section 11). */
public class NotificationDeliveryEntity {
  private Long id;
  private Long notificationId;
  private String channel;
  private String status;
  private String providerMessageId;
  private Integer retryCount;
  private String lastError;
  private java.time.OffsetDateTime sentAt;
  private java.time.OffsetDateTime createdAt;
  private java.time.OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getNotificationId() { return notificationId; }
  public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getProviderMessageId() { return providerMessageId; }
  public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
  public Integer getRetryCount() { return retryCount; }
  public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
  public String getLastError() { return lastError; }
  public void setLastError(String lastError) { this.lastError = lastError; }
  public java.time.OffsetDateTime getSentAt() { return sentAt; }
  public void setSentAt(java.time.OffsetDateTime sentAt) { this.sentAt = sentAt; }
  public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(java.time.OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(java.time.OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
