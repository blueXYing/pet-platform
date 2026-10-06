package com.petplatform.notification.biz.delivery.spi;

import com.petplatform.notification.biz.delivery.WechatDeliveryRequest;

/**
 * NTF-002 external WeChat delivery SPI (contract 55). One call models one channel send attempt;
 * retrying/backoff/dead-lettering stays with the durable task handler, never with the adapter.
 *
 * <p>Contract: implementations must not throw for provider-side outcomes — they return a typed
 * outcome instead. A thrown RuntimeException is defensive-mapped to a retryable failure by the
 * handler. Implementations must treat {@link WechatDeliveryRequest#dedupKey()} as the idempotency
 * key of the whole delivery (stable across task attempts) and must never block on unbounded I/O.
 * V1 ships no real adapter: production WeChat credentials/SDK and the subscribe-message template
 * policy are a separate authorization (default OFF).
 */
public interface WechatDeliveryAdapter {

  WechatDeliveryOutcome deliver(WechatDeliveryRequest request);
}
