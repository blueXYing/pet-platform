package com.petplatform.notification.biz.delivery.spi;

/**
 * NTF-002 terminal outcome of one adapter invocation. Error/reason codes are opaque technical
 * codes (1..64 chars) stored on notification_delivery.last_error; they never carry raw provider
 * payloads or user content.
 */
public sealed interface WechatDeliveryOutcome
    permits WechatDeliveryOutcome.Sent,
            WechatDeliveryOutcome.RetryableFailure,
            WechatDeliveryOutcome.PermanentFailure,
            WechatDeliveryOutcome.Skipped {

  record Sent(String providerMessageId) implements WechatDeliveryOutcome {
    public Sent {
      if (providerMessageId == null
          || providerMessageId.isBlank()
          || providerMessageId.length() > 128) {
        throw new IllegalArgumentException("Invalid provider message id");
      }
    }
  }

  record RetryableFailure(String errorCode) implements WechatDeliveryOutcome {
    public RetryableFailure {
      if (!validCode(errorCode)) throw new IllegalArgumentException("Invalid retryable error code");
    }
  }

  record PermanentFailure(String errorCode) implements WechatDeliveryOutcome {
    public PermanentFailure {
      if (!validCode(errorCode)) throw new IllegalArgumentException("Invalid permanent error code");
    }
  }

  record Skipped(String reasonCode) implements WechatDeliveryOutcome {
    public Skipped {
      if (!validCode(reasonCode)) throw new IllegalArgumentException("Invalid skip reason code");
    }
  }

  private static boolean validCode(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= 64
        && value.codePoints().noneMatch(Character::isISOControl);
  }
}
