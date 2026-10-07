> 2026-09-29: approved [Contract 46](../04-api/46-Order-Reschedule-Contract-v0.1.md) adds OrderRescheduledEvent.v1 and extends OrderConfirmedEvent.v1 / OrderRejectedEvent.v1 confirmRound to 0/1. See its exact payload/nullability and transaction rules. Notification consumption remains unimplemented.

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
| OrderConfirmedEvent.v1 | ORDER | order | notification（2026-09-29首轮内部生产者见44号契约；通知消费后续交付） |
| OrderRescheduledEvent.v1 | ORDER | order | notification (future; Contract 46) |
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

### 首轮 OrderConfirmedEvent.v1（2026-09-29 已批准）

正式实施边界见[44号契约](../04-api/44-Auto-Confirm-Execution-Recovery-Contract-v0.1.md)。标准 envelope：eventVersion=1、aggregateType=ORDER、aggregateId=orderId、occurredAt=confirmedAt；payload：

```java
public record OrderConfirmedPayload(
    String orderId,
    String reservationId,
    String storeId,
    int confirmRound,                 // 本轮仅0
    String confirmMode,               // AUTO；45号批准的主账号确认使用MERCHANT
    OffsetDateTime confirmDeadline,   // 原渠道paidAt+30min，UTC毫秒
    OffsetDateTime confirmedAt        // DB UTC毫秒
) {}
```

ORDER 状态、唯一成功证明和 Outbox 同事务提交。重放不产生第二个事件；通知等消费方仍按(eventId,consumerName)幂等，本切片未增加消费者。默认不启用生产者/Worker/修复扫描，不将此事件等同于用户消息已送达。

### 首轮 OrderRejectedEvent.v1（45号契约）

2026-09-29 用户批准D1/D2/D3。标准envelope为eventVersion=1、aggregateType=ORDER、aggregateId=orderId、occurredAt=rejectedAt。payload精确包含orderId/reservationId/storeId/decisionId/refundOrderId（String）、confirmRound（整数0）、rejectedAt（UTC毫秒OffsetDateTime）、reasonCode（45号五类枚举）。不广播reasonText/internalNote。

拒单决定、订单取消、FULL/MERCHANT_REJECT_ORDER退款单、执行绑定、任务、日志及事件同事务提交。退款消费者不得凭此事件重复创建退款。通知/授权原因读侧尚未交付。RefundOrderCreatedEvent.v1、RefundSucceededEvent.v1沿既有字段，以source/refundSource区分MERCHANT_REJECT_ORDER与LATE_PAYMENT_TIMEOUT；sourceEventId从REFUND不可变执行事实读取，不扩大严格payload。成功事件经来源证明校验后，ORDER金额投影、消费claim和SCH预约释放同事务提交。已知其他来源不归本消费者处理，未知来源失败。见[45号契约](../04-api/45-Merchant-Order-Actions-Contract-v0.1.md)。

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

## 商家申请审核结果事件（S5组件实现，默认未启用）

新增审阅定义：eventType固定MerchantApplicationReviewedEvent.v1，eventVersion=1，aggregateType=MERCHANT_APPLICATION，aggregateId=applicationId；沿用标准IntegrationEvent envelope，eventId为Snowflake String，occurredAt/decidedAt为毫秒OffsetDateTime，traceId只在envelope。

payload字段：applicationId、applicationNo（SQ+YYYYMMDD+8位随机码）、ownerUserId、reservedMerchantId、submittedRevisionId、reviewDecisionId、decisionType、applicationStatus、applicantVisibleOpinion、decidedAt。APPROVE对应APPROVED，REJECT/REQUEST_CORRECTION对应REJECTED；后两者意见10～500字符必填，通过意见可空。结构不含internalNote、证件原文、手机号、OCR原包、审核员登录账号或长期URL；意见也不得粘贴敏感原文。

MER审核决定、状态、审计、Outbox意图在同一事务；回滚均无事件，成功重放不产生新事件。notification按(eventId,consumerName)去重，并在本域同事务写消费日志与ownerUserId的真实站内消息。私有字段不能透传；通知持久化失败必须重试，不可先标已消费。查询页面不能代替强制站内通知。

对应严格payload schema见OpenAPI11的MerchantApplicationReviewedPayload（仅共享数据定义，不是HTTP endpoint）。S5在申请决定事务中调用IntegrationEventPublisher，并实现notification域消费者：严格payload校验、同事务消费去重/站内消息写入、UTC DATETIME存储及失败重试。消费者受Outbox和申请通知两个显式开关控制，默认未启用，无Scheduler改动。外部微信推送、真实用户消息页面和受控跳转仍未交付；组件测试不等于MER-001完整DoD。

## 服务审核结果事件（ADM-001 服务写入方，2026-09-22 已批；通知消费侧由通知域切片承接）

新增定义：eventType 固定 `ServiceReviewedEvent.v1`，eventVersion=1，aggregateType=`SERVICE`，aggregateId=serviceId；沿用标准 IntegrationEvent envelope（eventId 为 Snowflake String、occurredAt/decidedAt 毫秒 OffsetDateTime、traceId 只在 envelope）。

payload 字段（9 字段，与角色E消费侧对齐定稿 2026-09-22，中途不变卦）：`serviceId、serviceName、merchantId、storeId、submissionNo（JSON 整数）、decisionType(APPROVE|REJECT)、opinion?（REJECT 必填 10-500 字、APPROVE 可空）、decidedAt、ownerUserId`。ownerUserId 为商家主账号收件人（服务侧在创建服务时落 owner_user_id，33号），使消费者自包含、无需读 merchant 表。结构不含审核员账号、内部备注或任何长期 URL；意见不得粘贴敏感原文。

服务审核决定、状态、审计、Outbox 意图在同一事务；回滚均无事件，成功幂等重放不产生新事件。通知域消费者按 (eventId, consumerName) 去重并在本域同事务写消费日志与 ownerUserId 的商家站内消息；消费者与开关（`pet.service.review.notifications-enabled`，默认关闭）由通知域切片随其 PR 装配。消费者未接通前，完整服务审核流程不标完成。强制下架（FORCE_OFFLINE）是否通知商家＝剩余问题，本轮不发事件。

## VerificationRiskLockedEvent.v1
执行[47号契约](../04-api/47-Verification-Credential-Contract-v0.1.md)：VERIFICATION/orderId，payload为orderId/storeId/triggerAttemptId/lockedAt/lockedUntil/reasonCode，原因INVALID_CREDENTIAL_THRESHOLD；ID为String。一次新锁定与attempt/锁/首回执同事务Outbox，重试不重复告警。无明文码/个人信息，通知送达未交付。

## OrderVerifiedEvent.v2

执行 [48号契约](../04-api/48-Verification-Completion-Contract-v0.1.md)，K1/K2 已批准。ORDER 唯一生产，aggregate=ORDER/orderId，eventVersion=2；payload 固定 9 字段：orderId、verificationId、merchantId、storeId、operatorType、operatorId、membershipKind、operatorStaffId、verifiedAt。ID 均 String，OWNER 为 USER + 真实 userId + OWNER + null staffId，时间为 UTC 毫秒精度。

核销、订单完成、当前未履约售后失效、日志、首回执与事件同事务；重试不重复发布。无明文码或个人资料。v1 结构不改写，v2 通知/评价投影消费者尚未交付，不能以异步消费替代同步互斥。

## RefundApplicationCreatedEvent.v1 / RefundApplicationDecidedEvent.v1

2026-09-30 R1/R2 已批准，执行 [49号契约](../04-api/49-Refund-Application-Contract-v0.1.md)。两事件均由 REFUND 唯一生产，标准 envelope 的 eventVersion=1、aggregateType=`REFUND_APPLICATION`、aggregateId=applicationId；eventId 为新的 Snowflake String，traceId 只在 envelope。时间采用 UTC 毫秒精度，ID 为十进制 String，不增加自由文本或手机号。

| eventType | 精确 payload 字段 | 事务与含义 |
|---|---|---|
| `RefundApplicationCreatedEvent.v1` | applicationId、orderId、userId、merchantId、storeId、applicationStatus、merchantDeadline、createdAt | applicationStatus 普通路径固定 PENDING_MERCHANT，服务前即时批准（REF-001，来源 PRESTART_AUTO）按提交时真实终态发布 AUTO_APPROVED（2026-10-05 用户裁决）；merchantDeadline=createdAt+24h；occurredAt=createdAt。与申请、ORDER 引用、超时任务和首回执同事务唯一生产。 |
| `RefundApplicationDecidedEvent.v1` | applicationId、decisionId、orderId、userId、merchantId、storeId、applicationStatus、decidedAt | applicationStatus 为 APPROVED / REJECTED / AUTO_APPROVED；occurredAt=decidedAt。与不可变决定、ORDER 投影、首回执及批准时的唯一建单恢复任务同事务生产。 |

幂等重放不产生新事件，拒绝后新的申请是新聚合并重新计算期限，旧事件不得改写新一轮当前引用。事件表达可靠通知意图，不构成退款授权；说明/拒绝原因继续保存在本域受保护事实中，不透传事件。站内通知消费者、模板和实际送达仍后续验收，不能以已写 Outbox 宣称通知完成。

## 49号普通退款来源对既有事件的兼容

`RefundOrderCreatedEvent.v1` 的 `source` 和 `RefundSucceededEvent.v1` 的 `refundSource` 增加已批准的 `MERCHANT_APPROVED` / `MERCHANT_TIMEOUT_AUTO` 值，其他原 payload 字段保持；applicationId/decisionId 从可信内部事实读取，不追加到 v1，也不伪装 sourceEventId。普通创建事件随退款单、执行绑定、ORDER 提交证明和渠道任务同提交。2026-10-05 用户裁决（PR #103 阻塞项①④）：服务前自动全额退款（REF-001）新增第三种已批准来源 `PRESTART_AUTO`，同样只出现在既有 source/refundSource 字段，不复用 `MERCHANT_TIMEOUT_AUTO`；通知/优惠券/积分等消费者后续交付按此对齐。

`ORDER_APPLICATION_REFUND` 消费 `RefundSucceededEvent.v1`，先核对事件与 REFUND 最终渠道成功、ORDER 本域普通来源/原本金/身份/时间/唯一成功事件，随后在同 DataSource 事务内完成消费 claim、ORDER 已退款金额、成功证明与 SCHEDULE 原预约释放。任何一步失败全部回滚；重复事件核对原证明后幂等，UNKNOWN/FAILED 不释放。核销历史保持。

旧迟到、商家拒单和新普通来源消费者只跳过已知其他来源，未知来源或损坏 payload 失败关闭；不能把普通成功事件交给旧来源路径授权资金或永久重试。优惠券、积分等后续消费仍按各自契约验收，本次成功释放不代表全部退款后置流程已完成。


## 2026-09-30 AFS来源与进度增量（批准）

执行[Contract50 §8](../04-api/50-AfterSale-Workflow-Contract-v0.1.md)：AFTERSALE_DECISION加入退款v1严格来源，FULL/PARTIAL来自可信决定/执行事实；不新增v1字段。旧消费者先分派来源再校验本来源FULL。新增AfterSaleProgressChangedEvent.v1精确字段及动作见50号；Created/Resolved/Invalidated保持原载荷，AFS核销失效同事务发事件。事件不授权出款，通知实际送达另验。

## 员工邀请与成员生命周期事件（NTF切片，2026-10-06）

执行[54号契约](../04-api/54-Merchant-Staff-Binding-Contract-v0.1.md)站内通知接入切片。两个新事件由 MER 唯一生产（MerchantStaffMemberService 写事务内 TransactionalOutboxPublisher），标准 envelope：eventId 为新的 Snowflake String，traceId 只在 envelope，occurredAt/载荷时间为 UTC 毫秒精度，ID 为十进制 String。

| eventType | eventVersion / aggregate | 精确 payload 字段 | 事务与含义 |
|---|---|---|---|
| `MerchantStaffInvitationLifecycleEvent.v1` | 1 / `MERCHANT_MEMBER_INVITATION`，aggregateId=invitationId | invitationId、merchantId、storeId、ownerUserId、memberName、phoneMasked、changeType(INVITED\|CANCELED\|CONFIRMED)、confirmedUserId?（仅CONFIRMED）、memberId?（仅CONFIRMED）、occurredAt | invite/cancel/confirm 各自在同一命令事务内与邀请行、动作行、审计、23号幂等回执原子提交；回滚无事件，成功重放不产生新事件。载荷只含预脱敏手机号（`1xx****xxxx`）与 owner 可见姓名，无明文手机号。 |
| `MerchantStaffMemberLifecycleEvent.v1` | 1 / `MERCHANT_MEMBER`，aggregateId=memberId | memberId、merchantId、storeId、memberUserId、changeType(DISABLED\|ENABLED)、occurredAt | OWNER disable/enable 命令事务内原子生产；语义同上。 |

通知域消费（NTF-001 切片）：消费者 `notification.merchant-staff-invitation.v1` / `notification.merchant-staff-member.v1` 按 (eventId, consumerName) 去重并在本域同事务写消费日志与站内行；开关 `pet.merchant.staff.notifications-enabled`（默认关闭）独立于 54号写侧开关与 outbox 总开关，装配见 pet-boot EventOutboxConfiguration。**可达性边界（SQL06 §11 依据）**：站内行只能按 USER 账号 id 寻址，被邀人确认前尚无 user_account（54号 §2），因此 INVITED/CANCELED 仅通知 OWNER（邀请编号随站内行落库，供商家转发与自查），CONFIRMED 通知 OWNER 与确认员工本人，DISABLED/ENABLED 通知被绑定的成员账号。grantActions/revokeStoreGrant 不在本切片事件范围。消费者自包含（收件人来自事件载荷），不读 merchant 表（ARCH-002）。生产侧不设独立开关：`pet.outbox.enabled=false`（默认）时无 publisher bean、零事件零行为。
