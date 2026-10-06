package com.petplatform.notification.biz.delivery.spi;

import com.petplatform.notification.biz.delivery.WechatDeliveryRequest;
import java.util.Objects;

/**
 * Default production shell when the delivery switch is on but no real WeChat adapter is bound
 * (V1 baseline: no WeChat SDK, no credentials, no outbound network call). Every attempt is a
 * terminal skip so the durable task closes cleanly and the delivery row keeps an auditable
 * reason instead of burning retry budget against a channel that cannot exist yet.
 */
public final class UnconfiguredWechatDeliveryAdapter implements WechatDeliveryAdapter {

  public static final String REASON_CODE = "WECHAT_CHANNEL_UNCONFIGURED";

  @Override
  public WechatDeliveryOutcome deliver(WechatDeliveryRequest request) {
    Objects.requireNonNull(request, "request is required");
    return new WechatDeliveryOutcome.Skipped(REASON_CODE);
  }
}
