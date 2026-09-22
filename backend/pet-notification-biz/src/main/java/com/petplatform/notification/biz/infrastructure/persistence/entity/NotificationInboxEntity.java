package com.petplatform.notification.biz.infrastructure.persistence.entity;

/** Read projection of the notification inbox row (schema 06 section 11). */
public class NotificationInboxEntity {
  private Long id;
  private String receiverType;
  private Long receiverId;
  private String category;
  private String messageType;
  private String bizType;
  private Long bizId;
  private String title;
  private String content;
  private Boolean mandatoryInbox;
  private java.time.OffsetDateTime readAt;
  private java.time.OffsetDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getReceiverType() { return receiverType; }
  public void setReceiverType(String receiverType) { this.receiverType = receiverType; }
  public Long getReceiverId() { return receiverId; }
  public void setReceiverId(Long receiverId) { this.receiverId = receiverId; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getMessageType() { return messageType; }
  public void setMessageType(String messageType) { this.messageType = messageType; }
  public String getBizType() { return bizType; }
  public void setBizType(String bizType) { this.bizType = bizType; }
  public Long getBizId() { return bizId; }
  public void setBizId(Long bizId) { this.bizId = bizId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public Boolean getMandatoryInbox() { return mandatoryInbox; }
  public void setMandatoryInbox(Boolean mandatoryInbox) { this.mandatoryInbox = mandatoryInbox; }
  public java.time.OffsetDateTime getReadAt() { return readAt; }
  public void setReadAt(java.time.OffsetDateTime readAt) { this.readAt = readAt; }
  public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(java.time.OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
