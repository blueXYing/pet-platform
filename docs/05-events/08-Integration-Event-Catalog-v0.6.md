# 宠物平台 V1.0 Integration Event Catalog v0.6

> 技术目标：当前模块化单体使用 Transactional Outbox；未来切 MQ 时保持事件名称、版本和业务语义稳定。

## 1. Event Envelope

```java
public record IntegrationEvent<T>(
    String eventId,
    String eventType,
    int eventVersion,
    OffsetDateTime occurredAt,
    String aggregateType,
    String aggregateId,
    String traceId,
    T payload
) {}
```

唯一幂等：

```text
UNIQUE(event_id, consumer_name)
```

事件只表达“已经发生的事实”，不使用 `DoXxxEvent` 这种命令式命名。

## 2. 首批事件

| Event | Aggregate | 主要生产者 | 主要消费者 |
|---|---|---|---|
| PaymentSucceededEvent.v1 | PAYMENT | payment | order |
| LatePaymentSucceededAfterTimeoutEvent.v1 | ORDER/PAYMENT | order | refund/notification |
| PaymentFailedEvent.v1 | PAYMENT | payment | order/notification |
| OrderPaidEvent.v1 | ORDER | order | schedule/coupon/notification |
| OrderPaymentExpiredEvent.v1 | ORDER | order | schedule/coupon/notification |
| OrderConfirmedEvent.v1 | ORDER | order | notification |
| OrderRejectedEvent.v1 | ORDER | order | refund/notification |
| OrderVerifiedEvent.v1 | ORDER | verification/order | aftersale/review/notification |
| RefundOrderCreatedEvent.v1 | REFUND | refund | order/notification |
| RefundSucceededEvent.v1 | REFUND | refund | order/schedule/coupon/points/review/notification |
| RefundFailedEvent.v1 | REFUND | refund | notification/admin |
| AfterSaleCreatedEvent.v1 | AFTERSALE | aftersale | order/notification |
| AfterSaleResolvedEvent.v1 | AFTERSALE | aftersale | order/notification |
| AfterSaleInvalidatedEvent.v1 | AFTERSALE | aftersale | order/notification |
| MerchantDisabledEvent.v1 | MERCHANT | merchant/admin | order/service/schedule/notification |
| ReviewCreatedEvent.v1 | REVIEW | review | merchant/notification |

## 3. PaymentSucceededEvent.v1

```java
public record PaymentSucceededPayload(
    String paymentOrderId,
    String orderId,
    String channelTradeNo,
    BigDecimal paidAmount,
    OffsetDateTime paidAt
) {}
```

消费者要求：

- 正常待支付订单：order 幂等标记支付成功，进入 `PENDING_CONFIRM`，后续生成 `OrderPaidEvent.v1`。
- 若订单已经因为 `PAYMENT_TIMEOUT` 关闭：不恢复订单，也不生成正常 `OrderPaidEvent`；记录真实支付事实后生成 `LatePaymentSucceededAfterTimeoutEvent.v1`。
- 重复支付回调不能重复推进订单或重复创建退款单。

## 4. OrderPaidEvent.v1

```java
public record OrderPaidPayload(
    String orderId,
    String reservationId,
    String couponInstanceId,
    OffsetDateTime paidAt,
    OffsetDateTime confirmDeadline
) {}
```

消费者：

- schedule：临时 reservation → 正式占用。
- coupon：冻结券 → 已使用。
- notification：生成支付成功/待确认站内通知。

`confirmDeadline = paidAt + 30 minutes`。

## 5. OrderPaymentExpiredEvent.v1

```java
public record OrderPaymentExpiredPayload(
    String orderId,
    String paymentOrderId,
    String reservationId,
    String couponInstanceId,
    OffsetDateTime expiredAt
) {}
```

生产前提：

```text
订单支付窗口已到
+
支付渠道已经确认未成功/已关闭
```

如果渠道结果仍然 `UNKNOWN`，禁止生产该事件，必须继续主动查单。

消费者：

- schedule：释放仍占用的预约资源；
- coupon：释放仍冻结的订单优惠券；
- notification：按产品需要生成订单关闭类站内通知。

该事件不能用于处理“渠道可能已经支付但回调迟到”的不确定场景。

---

## 6. OrderVerifiedEvent.v1

```java
public record OrderVerifiedPayload(
    String orderId,
    String verificationId,
    String merchantId,
    String storeId,
    String staffId,
    OffsetDateTime verifiedAt
) {}
```

消费者：

- aftersale：若存在当前“未履约型售后”，置 `INVALIDATED`。
- notification：核销成功站内消息。
- review：不直接创建评价，只建立/刷新评价资格读取所需事实。

核销后售后窗口：

```text
verifiedAt + 7 days
```

评价窗口：

```text
verifiedAt + 30 days
```

## 7. RefundOrderCreatedEvent.v1

```java
public record RefundOrderCreatedPayload(
    String refundOrderId,
    String refundNo,
    String orderId,
    RefundType refundType,
    BigDecimal refundAmount,
    String source,
    OffsetDateTime createdAt
) {}
```

业务事实：

```text
该事件出现 = refund_order 已在本地事务成功创建
```

从此订单必须禁止后续核销。

注意：反方向并不对称。核销先成功只会使“当前未履约型售后”失效，不代表该订单未来永远不能产生合法退款；核销后的商家同意全额退款或新的核销后售后裁决仍按产品规则处理。

## 8. RefundSucceededEvent.v1

```java
public record RefundSucceededPayload(
    String refundOrderId,
    String refundNo,
    String orderId,
    RefundType refundType,
    String refundSource,
    BigDecimal refundAmount,
    BigDecimal originalPaidAmount,
    String channelRefundNo,
    OffsetDateTime succeededAt
) {}
```

消费者：

### order

```text
FULL    → 已退款展示态
PARTIAL → 部分退款展示态
```

### schedule

```text
退款最终成功后才释放仍占用的预约资源
```

### coupon

```text
FULL:
  按返券规则恢复；
  若剩余有效期 < 24h，延长到 succeededAt + 24h

PARTIAL:
  不返券

LATE_PAYMENT_TIMEOUT:
  支付超时关闭时优惠券已经释放
  不再次恢复或抢回优惠券
```

### points

```text
FULL:
  扣回本单全部奖励积分

PARTIAL:
  本单奖励积分 × refundAmount/originalPaidAmount
  四舍五入扣回
```

### review

```text
PARTIAL + 已核销：
  已有评价 → scoreIncluded=false
  尚未评价 → 之后资格结果 scoreIncluded=false

PARTIAL + 未核销：
  不产生评价资格
```

### notification

生成退款结果站内通知；外部推送依据用户偏好与微信能力处理。

## 9. AfterSaleCreatedEvent.v1

```java
public record AfterSaleCreatedPayload(
    String afterSaleId,
    String orderId,
    String userId,
    String afterSaleType,
    OffsetDateTime createdAt
) {}
```

order 消费：

```text
记录存在活动售后事实
```

注意：该事件本身**不禁止核销**。

## 10. AfterSaleResolvedEvent.v1

```java
public record AfterSaleResolvedPayload(
    String afterSaleId,
    String orderId,
    AfterSaleDecisionType decisionType,
    BigDecimal decisionRefundAmount,
    OffsetDateTime decidedAt
) {}
```

说明：

- `FULL_REFUND / PARTIAL_REFUND` 的真正资金执行由 aftersale 同步调用 refund-api 发起。
- 退款能否成立仍以 `refund_order` 成功创建为边界。
- 不能仅凭 `AfterSaleResolvedEvent` 判断“核销已禁止”。

## 11. AfterSaleInvalidatedEvent.v1

```java
public record AfterSaleInvalidatedPayload(
    String afterSaleId,
    String orderId,
    String reasonCode,
    OffsetDateTime invalidatedAt
) {}
```

典型原因：

```text
VERIFICATION_WON_RACE
```

这里只是技术原因 code，不新增产品复审流程。

## 12. MerchantDisabledEvent.v1

```java
public record MerchantDisabledPayload(
    String merchantId,
    List<String> storeIds,
    OffsetDateTime disabledAt
) {}
```

消费者：

- service/schedule：停止新预约能力。
- order：存量订单不取消，继续按存量规则履约。
- notification/admin：必要告警/通知。

## 13. ReviewCreatedEvent.v1

```java
public record ReviewCreatedPayload(
    String reviewId,
    String orderId,
    String merchantId,
    String storeId,
    String serviceId,
    String staffId,
    boolean scoreIncluded,
    OffsetDateTime createdAt
) {}
```

评分聚合仅消费：

```text
scoreIncluded=true
```

并执行近 12 个月有效评价算法。

## 14. Outbox 状态

建议：

```text
NEW
PUBLISHING
PUBLISHED
FAILED
```

字段至少包含：

```text
event_id
event_type
event_version
aggregate_type
aggregate_id
payload_json
occurred_at
publish_status
retry_count
next_retry_at
published_at
```

## 15. 消费幂等

每个消费者本地事务：

```text
BEGIN
  INSERT consume_log(event_id, consumer_name)
  若唯一键冲突 → 已消费，直接成功
  执行业务变更
COMMIT
```

不能采用：

```text
先处理业务
再单独写 consume_log
```

否则可能重复消费。

## 16. 事件兼容规则

```text
1. 已发布的 v1 字段不得改变语义。
2. 新增可选字段优先保持同版本向后兼容。
3. 删除字段或改变含义 → 新事件版本。
4. 消费者按 eventType + eventVersion 路由。
5. payload 不放数据库 Entity 全量序列化结果。
6. 事件仅携带消费者真正需要的稳定业务事实。
```

---

## 17. LatePaymentSucceededAfterTimeoutEvent.v1

支付渠道确认成功，但平台订单已经因支付超时关闭时，由 order 模块发布：

```java
public record LatePaymentSucceededAfterTimeoutPayload(
    String orderId,
    String paymentOrderId,
    String paymentNo,
    BigDecimal channelPaidAmount,
    OffsetDateTime channelPaidAt,
    OffsetDateTime detectedAt
) {}
```

refund 消费：

```text
幂等创建 FULL refund_order
refund_source = LATE_PAYMENT_TIMEOUT
refund_amount = channelPaidAmount
↓
提交原路退款
```

强制约束：

```text
不发布正常 OrderPaidEvent
不恢复 reservation
不重新冻结 coupon
不创建 30 分钟自动接单任务
不创建第二张订单
```

若退款暂时异常，继续使用 Retry / Channel Query / Reconciliation 完成退款。
