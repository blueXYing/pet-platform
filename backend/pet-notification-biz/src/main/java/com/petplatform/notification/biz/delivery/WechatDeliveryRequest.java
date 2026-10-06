package com.petplatform.notification.biz.delivery;

import java.util.Objects;

/**
 * NTF-002 external WeChat delivery request, assembled strictly from the authoritative inbox row
 * (SSOT §16.3: the inbox message is the authoritative record; WeChat is only an external reminder
 * channel). channel is one of the Schema 06 §11 delivery channels; dedupKey is the deterministic
 * durable-task requestId so a real provider can dedupe at-least-once retries.
 */
public record WechatDeliveryRequest(
    long notificationId,
    String channel,
    long receiverUserId,
    String title,
    String content,
    String bizType,
    Long bizId,
    String dedupKey) {

  public WechatDeliveryRequest {
    Objects.requireNonNull(channel, "channel is required");
    Objects.requireNonNull(title, "title is required");
    Objects.requireNonNull(content, "content is required");
    if (notificationId <= 0
        || receiverUserId <= 0
        || channel.isBlank()
        || channel.codePoints().anyMatch(Character::isISOControl)
        || title.isEmpty()
        || title.length() > 128
        || content.isEmpty()
        || content.length() > 1000
        || dedupKey == null
        || dedupKey.isBlank()
        || dedupKey.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid WeChat delivery request");
    }
  }
}
