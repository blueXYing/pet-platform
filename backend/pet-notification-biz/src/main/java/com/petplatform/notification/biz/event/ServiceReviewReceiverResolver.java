package com.petplatform.notification.biz.event;

/** Resolves the merchant owner who receives the service review notification. */
@FunctionalInterface
public interface ServiceReviewReceiverResolver {

  /**
   * Returns the positive owner user id for the merchant, or a value <= 0 when the merchant
   * cannot currently be resolved (the consumer then fails the delivery for retry).
   */
  long ownerUserIdOf(long merchantId);
}
