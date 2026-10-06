package com.petplatform.notification.biz;

import com.petplatform.notification.biz.delivery.WechatDeliveryRequest;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryAdapter;
import com.petplatform.notification.biz.delivery.spi.WechatDeliveryOutcome;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Test-only controlled fake: scripted outcomes, recorded requests, zero network. */
final class ScriptedWechatDeliveryAdapter implements WechatDeliveryAdapter {
  private final Deque<WechatDeliveryOutcome> script = new ArrayDeque<>();
  final List<WechatDeliveryRequest> requests = new ArrayList<>();
  private WechatDeliveryOutcome exhausted = new WechatDeliveryOutcome.Skipped("SCRIPT_EXHAUSTED");
  private RuntimeException thrown;

  ScriptedWechatDeliveryAdapter then(WechatDeliveryOutcome outcome) {
    script.add(Objects.requireNonNull(outcome));
    return this;
  }

  ScriptedWechatDeliveryAdapter whenExhausted(WechatDeliveryOutcome outcome) {
    this.exhausted = Objects.requireNonNull(outcome);
    return this;
  }

  ScriptedWechatDeliveryAdapter alwaysThrow(RuntimeException failure) {
    this.thrown = Objects.requireNonNull(failure);
    return this;
  }

  @Override
  public WechatDeliveryOutcome deliver(WechatDeliveryRequest request) {
    requests.add(Objects.requireNonNull(request));
    if (thrown != null) throw thrown;
    return script.isEmpty() ? exhausted : script.pop();
  }
}
