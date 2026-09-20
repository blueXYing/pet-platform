# 宠物平台 V1.0 内部 API Contract v0.6

> 文档定位：定义模块化单体阶段的跨模块 Java API 契约。当前由 `LocalXxxApiImpl` 实现；未来拆分微服务时，由 `FeignXxxApiAdapter` 实现同一契约。  
> 优先级：产品 SSOT > 技术基线 > 本 API Contract > 具体实现。  
> 强制约束：`xxx-biz` 只能依赖其他模块 `yyy-api`，禁止 `biz -> biz`。

---

## 1. API 层边界原则

1. API 层只暴露“业务能力”，不暴露 Repository、Mapper、DO/Entity。
2. API DTO 不允许引用 MyBatis/JPA 实体。
3. API 契约中所有 ID 均使用 `String`；模块内部落库使用 `BIGINT/Long`。
4. 写操作全部携带 `requestId`，用于幂等。
5. API 方法不返回数据库实体；返回稳定 DTO / Result。
6. 查询接口不改变业务状态；命令接口必须有明确业务动词。
7. 不设计 `updateOrder(OrderDTO)`、`saveXxx()` 这类通用修改接口。
8. Feign、HTTP、序列化细节不进入核心 Contract；未来由 Adapter 负责。
9. 跨模块 API 不依赖一个“大事务”；跨模块后置动作优先通过 Integration Event。
10. 产品展示状态 `DisplayOrderStatus` 由 order 模块统一计算，前端及其他模块不得重复实现。

---

## 2. 公共契约

### 2.1 CommandContext

公共字段细化按[23号补充§1～3](23-公共接口与幂等契约补充-v0.1.md)。本record保持五字段；可信actor/scope由真实适配器解析，source不授予权限。S1仅具备字段检查，未实现认证、授权或持久化幂等。

```java
public record CommandContext(
    String requestId,
    String traceId,
    OperatorType operatorType,
    String operatorId,
    String source
) {}
```

`OperatorType`：

```java
USER,
MERCHANT_STAFF,
PLATFORM_OPERATOR,
SYSTEM
```

说明：

- `requestId`：业务幂等键，必填。
- `traceId`：链路追踪 ID。
- `operatorId`：API 传输为 String。
- `source`：`MINI_PROGRAM / MERCHANT_APP / ADMIN / JOB / EVENT_CONSUMER` 等技术来源，不参与产品状态判断。

### 2.2 QueryContext

```java
public record QueryContext(
    String traceId,
    OperatorType operatorType,
    String operatorId
) {}
```

### 2.3 时间与金额

```java
OffsetDateTime  // API 时间语义
BigDecimal      // 金额
String          // 所有 ID
```

金额禁止 `double/float`。

ID按ASCII正Long十进制String传输；Money定点输入/两位输出与无舍入规则、Clock注入及时间毫秒精度见23号补充§1～2。内部BigDecimal可精确格式化，不把HTTP字符串词法与已解析数值混同。业务负数/零资格、比例与积分舍入不由公共Codec决定。

### 2.4 本地调用与未来 Feign

当前：

```text
refund-biz
   ↓ compile dependency
order-api
   ↓ runtime bean
LocalOrderQueryApiImpl
   ↓
order-biz
```

未来：

```text
refund-service
   ↓
order-api
   ↓
FeignOrderQueryApiAdapter
   ↓ HTTP
order-service
```

调用业务代码仍依赖 `OrderQueryApi`，不直接依赖 Feign Client。

---

## 3. pet-user-api

### 3.1 UserQueryApi

```java
public interface UserQueryApi {

    UserBasicDTO getUser(UserIdQuery query);

    PetSnapshotDTO getPetSnapshot(PetSnapshotQuery query);

    boolean existsEnabledUser(UserIdQuery query);
}
```

DTO：

```java
public record UserBasicDTO(
    String userId,
    String mobileMasked,
    String nickname,
    UserStatus status
) {}

public record PetSnapshotDTO(
    String petId,
    String ownerUserId,
    String name,
    String categoryCode,
    String breedName,
    String genderCode,
    BigDecimal weightKg,
    String healthRemark
) {}
```

边界：

- order 模块只能拿创建订单需要的宠物快照字段。
- 不允许 order 模块查询 `user_pet` 表。
- 健康备注属于订单快照时，只保存下单时的必要副本。
- getPetSnapshot（CCR-W2-API-001用户域0.1已批）：PetSnapshotQuery必须携带ownerUserId做归属校验；
  不存在/DISABLED/归属不符统一抛稳定码PET_NOT_FOUND；返回即时副本，调用方须自行持久化快照，
  后续主数据变化不影响已存副本。existsEnabledUser：FROZEN/CANCELED返回false。

---

## 4. pet-merchant-api

### 4.1 MerchantQueryApi

```java
public interface MerchantQueryApi {

    MerchantStoreDTO getStore(StoreIdQuery query);

    MerchantOrderEligibilityDTO checkOrderEligibility(
        MerchantOrderEligibilityQuery query
    );

    MerchantStaffDTO getStaff(MerchantStaffQuery query);
}
```

```java
public record MerchantOrderEligibilityDTO(
    String merchantId,
    String storeId,
    boolean merchantEnabled,
    boolean storeEnabled,
    boolean acceptsNewOrders
) {}
```

技术规则：

- 商家下线后 `acceptsNewOrders=false`。
- 已存在存量订单继续履约，不由此接口否决存量订单处理。
- “商家下线但存量订单继续履约”属于订单侧按订单快照与存量关系判断。

---

## 5. pet-service-api

### 5.1 ServiceQueryApi

```java
public interface ServiceQueryApi {

    ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query);

    ServiceBookabilityDTO checkBookable(ServiceBookabilityQuery query);
}
```

```java
public record ServiceSnapshotDTO(
    String serviceId,
    String merchantId,
    String storeId,
    String serviceName,
    String categoryId,
    String categoryName,
    BigDecimal salePrice,
    Integer durationMinutes,
    FulfillmentType fulfillmentType,
    String description
) {}
```

`FulfillmentType` 仅允许：

```java
IN_STORE,
PICKUP_DELIVERY
```

V1.0 API 契约不包含套餐、次卡、普通商品或无需履约类型。

---

## 6. pet-schedule-api

### 6.1 ScheduleQueryApi

```java
public interface ScheduleQueryApi {

    AvailabilityResult queryAvailability(AvailabilityQuery query);

    ReservationDTO getReservation(ReservationQuery query);
}
```

### 6.2 ScheduleCommandApi

```java
public interface ScheduleCommandApi {

    ReservationHoldResult hold(ReservationHoldCommand command);

    ReservationConfirmResult confirm(ReservationConfirmCommand command);

    ReservationReleaseResult release(ReservationReleaseCommand command);

    RescheduleResult swap(RescheduleCommand command);

    StaffAssignmentResult assignStaff(AssignStaffCommand command);
}
```

关键 Command：

```java
public record ReservationHoldCommand(
    CommandContext context,
    String userId,
    String merchantId,
    String storeId,
    String serviceId,
    OffsetDateTime appointmentStart,
    OffsetDateTime appointmentEnd,
    OffsetDateTime pickupStart,
    OffsetDateTime returnStart
) {}
```

规则：

- 时间精确到分钟。
- 不引入固定 60 分钟 slot contract。
- `PICKUP_DELIVERY` 时必须满足 `returnStart >= pickupStart + 120min`。
- 容量校验：`min(merchantConfiguredCapacity, qualifiedAvailableStaffCount)`。
- `hold` 成功后返回 reservationId + holdExpireAt。
- `swap` 必须保证新预约成功后才释放原预约；失败保留原预约。
- 退款申请、退款待确认、退款单创建、退款处理中均不释放预约。
- 仅 `RefundSucceededEvent` 后由 schedule 模块执行最终释放。

### 6.3 AvailabilityResult

```java
public record AvailabilityResult(
    String storeId,
    String serviceId,
    OffsetDateTime start,
    OffsetDateTime end,
    int configuredCapacity,
    int qualifiedAvailableStaffCount,
    int effectiveCapacity,
    int occupiedCount,
    int remainingCapacity,
    boolean available
) {}
```

---

## 7. pet-order-api

order API 拆为 Query / Command / OperationGuard 三类。

### 7.1 OrderQueryApi

```java
public interface OrderQueryApi {

    OrderSnapshotDTO getOrder(OrderIdQuery query);

    RefundEligibilityDTO checkRefundEligibility(
        RefundEligibilityQuery query
    );

    VerificationEligibilityDTO checkVerificationEligibility(
        VerificationEligibilityQuery query
    );

    ReviewEligibilityDTO checkReviewEligibility(
        ReviewEligibilityQuery query
    );

    DisplayOrderStatusDTO getDisplayStatus(
        DisplayOrderStatusQuery query
    );
}
```

### 7.2 OrderCommandApi

```java
public interface OrderCommandApi {

    OrderCreateResult create(OrderCreateCommand command);

    void markPaid(MarkOrderPaidCommand command);

    void confirmOrder(ConfirmOrderCommand command);

    void autoConfirmOrder(AutoConfirmOrderCommand command);

    void rejectOrder(RejectOrderCommand command);

    void markRefundApplicationPending(
        MarkRefundApplicationPendingCommand command
    );

    void restoreAfterRefundRejected(
        RestoreAfterRefundRejectedCommand command
    );

    void markRefundOrderCreated(
        MarkRefundOrderCreatedCommand command
    );

    void markVerified(MarkOrderVerifiedCommand command);

    void markRefundSucceeded(MarkRefundSucceededCommand command);

    void markPartialRefundSucceeded(
        MarkPartialRefundSucceededCommand command
    );

    void markAfterSaleActive(MarkAfterSaleActiveCommand command);

    void markAfterSaleInvalidated(
        MarkAfterSaleInvalidatedCommand command
    );
}
```

### 7.3 OrderOperationGuardApi

```java
public interface OrderOperationGuardApi {

    OperationGuardToken acquire(
        OrderOperationGuardCommand command
    );

    void commit(OperationGuardCommitCommand command);

    void release(OperationGuardReleaseCommand command);
}
```

`OrderOperationType`：

```java
VERIFY,
CREATE_REFUND
```

### 7.4 OrderSnapshotDTO

```java
public record OrderSnapshotDTO(
    String orderId,
    String orderNo,
    String userId,
    String merchantId,
    String storeId,
    String serviceId,
    String reservationId,

    OrderStage orderStage,
    PaymentStatus paymentStatus,
    RefundApplicationStatus refundApplicationStatus,
    RefundStatus refundStatus,
    AfterSaleStatus afterSaleStatus,
    VerificationStatus verificationStatus,
    DisplayOrderStatus displayStatus,

    BigDecimal originalAmount,
    BigDecimal discountAmount,
    BigDecimal payAmount,
    BigDecimal refundedAmount,

    OffsetDateTime appointmentStart,
    OffsetDateTime appointmentEnd,
    OffsetDateTime paidAt,
    OffsetDateTime confirmedAt,
    OffsetDateTime verifiedAt,

    int rescheduleCount,
    long version
) {}
```

### 7.5 RefundEligibilityDTO

```java
public record RefundEligibilityDTO(
    boolean eligible,
    RefundRoute route,
    BigDecimal maxRefundAmount,
    String rejectCode,
    OffsetDateTime merchantDeadline
) {}
```

`RefundRoute`：

```java
AUTO_FULL_BEFORE_SERVICE,
MERCHANT_CONFIRM_AFTER_SERVICE,
AFTERSALE_DECISION
```

补充强制语义：

```text
verificationStatus=VERIFIED 不是一个通用“禁止退款”条件。

refund_order 已创建
→ 永久禁止后续核销。

核销已成功
→ 当前未履约型售后必须失效；
→ 但不阻止核销后的合法全额退款或新的核销后售后裁决退款。
```

因此所有 `RefundEligibility` / `RefundCreate` 实现都必须结合 `RefundRoute + sourceBizId + aftersale 状态` 判断，不得只按 `verificationStatus` 单字段拒绝。

### 7.6 VerificationEligibilityDTO

```java
public record VerificationEligibilityDTO(
    boolean eligible,
    String rejectCode,
    String guardRequiredOperation
) {}
```

核销必须拒绝以下事实：

```text
refund_order 已创建
refund_status = CREATED / PROCESSING / SUCCESS
verification_status = VERIFIED
订单不是可履约阶段
```

售后处理中本身不是核销拒绝条件。

### 7.7 ReviewEligibilityDTO

```java
public record ReviewEligibilityDTO(
    boolean eligible,
    boolean scoreIncluded,
    OffsetDateTime reviewDeadline,
    String rejectCode
) {}
```

规则：

```text
必须已核销
reviewDeadline = verifiedAt + 30 days

已核销 + 后续部分退款
→ eligible=true（若仍在30天内）
→ scoreIncluded=false

未核销 + 部分退款
→ eligible=false
```

---

## 8. pet-payment-api

支付渠道由 payment 模块统一隔离，业务模块不能直接调用拉卡拉 SDK/HTTP。

### 8.1 PaymentApi

```java
public interface PaymentApi {

    PaymentCreateResult createPayment(PaymentCreateCommand command);

    PaymentDTO queryPayment(PaymentQuery query);

    PaymentCloseResult closePayment(PaymentCloseCommand command);
}
```

### 8.2 PaymentRefundApi

```java
public interface PaymentRefundApi {

    ChannelRefundSubmitResult submitRefund(
        ChannelRefundSubmitCommand command
    );

    ChannelRefundQueryResult queryRefund(
        ChannelRefundQuery query
    );
}
```

说明：

- `refund-biz` 可以依赖 `payment-api`，但不能依赖 `payment-biz`。
- `PaymentRefundApi` 是“支付渠道退款能力”，不等于业务 `RefundApi`。
- 拉卡拉退款成功后先由 refund 模块确认本地退款事实，再发布 `RefundSucceededEvent`。
- 渠道回调必须以渠道交易号 + 业务退款单号做幂等。

---

## 9. pet-refund-api

退款分为“退款申请”与“业务退款单”。

### 9.1 RefundQueryApi

```java
public interface RefundQueryApi {

    RefundApplicationDTO getApplication(
        RefundApplicationQuery query
    );

    RefundOrderDTO getRefund(RefundQuery query);

    boolean hasBusinessRefund(OrderRefundExistQuery query);
}
```

### 9.2 RefundCommandApi

```java
public interface RefundCommandApi {

    RefundApplicationResult apply(RefundApplyCommand command);

    RefundApplicationResult approveByMerchant(
        MerchantApproveRefundCommand command
    );

    RefundApplicationResult rejectByMerchant(
        MerchantRejectRefundCommand command
    );

    RefundOrderDTO createRefund(
        RefundCreateCommand command
    );

    RefundOrderDTO retryChannelRefund(
        RefundRetryCommand command
    );
}
```

### 9.3 RefundApplyCommand

```java
public record RefundApplyCommand(
    CommandContext context,
    String orderId,
    String userId,
    String reasonCode,
    String reasonText
) {}
```

说明：

- `reasonCode` 使用可配置字典，当前 Contract 不定义具体产品分类值。
- 服务开始前：自动审批，进入创建全额退款单流程。
- 服务开始后：创建 `PENDING_MERCHANT` 退款申请。
- 商家拒绝必须有 `reasonText`。
- 商家 24 小时未处理，由系统 Job 使用确定性 requestId 自动审批。
- 普通退款商家只能全额同意或拒绝。

### 9.4 RefundCreateCommand

```java
public record RefundCreateCommand(
    CommandContext context,
    String orderId,
    RefundType refundType,
    BigDecimal refundAmount,
    RefundSource source,
    String sourceBizId
) {}
```

`RefundType`：

```java
FULL,
PARTIAL
```

`RefundSource` 至少包含：

```java
USER_BEFORE_SERVICE,
MERCHANT_APPROVED,
MERCHANT_TIMEOUT_AUTO,
MERCHANT_REJECT_ORDER,
AFTERSALE_DECISION,
ADMIN_ABNORMAL_CLOSE,
LATE_PAYMENT_TIMEOUT
```

`LATE_PAYMENT_TIMEOUT`：

```text
订单已因 PAYMENT_TIMEOUT 关闭
后续才确认渠道真实支付成功
只允许 FULL
refundAmount = 渠道实际支付金额
原订单保持关闭
```

`PARTIAL` 只允许合法的运营售后裁决来源。

### 9.5 一单一次业务退款

`refund_order.order_id` 建唯一约束。

渠道重试不新建业务退款单，只追加 `refund_transaction`。

---

## 10. pet-verification-api

```java
public interface VerificationApi {

    VerificationEligibilityDTO check(
        VerificationCheckQuery query
    );

    VerificationResult verify(
        VerifyOrderCommand command
    );
}
```

### 10.1 VerifyOrderCommand

```java
public record VerifyOrderCommand(
    CommandContext context,
    String orderId,
    String storeId,
    String staffId,
    String verificationCode
) {}
```

强制流程：

```text
1. acquire order guard: VERIFY
2. 重新读取订单事实
3. 校验 refund_order 未创建
4. 校验当前可履约
5. 写 verification_record
6. order.markVerified
7. commit guard
8. 发布 OrderVerifiedEvent
```

如果存在“未履约类售后处理中”：

```text
OrderVerifiedEvent
→ aftersale 模块将当前未履约售后置 INVALIDATED
→ 不允许再基于该旧售后执行退款
→ 核销后重新开启 7 天正常售后窗口
```

---

## 11. pet-aftersale-api

### 11.1 AfterSaleQueryApi

```java
public interface AfterSaleQueryApi {

    AfterSaleDTO getCase(AfterSaleQuery query);

    AfterSaleEligibilityDTO checkEligibility(
        AfterSaleEligibilityQuery query
    );
}
```

### 11.2 AfterSaleCommandApi

```java
public interface AfterSaleCommandApi {

    AfterSaleDTO create(AfterSaleCreateCommand command);

    AfterSaleDTO requestSupplement(
        AfterSaleSupplementRequestCommand command
    );

    AfterSaleDTO submitEvidence(
        AfterSaleEvidenceCommand command
    );

    AfterSaleDecisionResult decide(
        AfterSaleDecisionCommand command
    );

    AfterSaleDTO invalidate(
        AfterSaleInvalidateCommand command
    );

    AfterSaleDTO withdraw(
        AfterSaleWithdrawCommand command
    );
}
```

`AfterSaleDecisionType`：

```java
FULL_REFUND,
PARTIAL_REFUND,
REJECT,
RESERVICE,
OTHER
```

没有售后复审 API。

### 11.3 决策退款

```text
运营 decide(FULL_REFUND / PARTIAL_REFUND)
→ aftersale-biz 调用 refund-api
→ refund-api 获取 CREATE_REFUND guard
→ refund_order 创建成功
→ 售后记录保存对应 refundOrderId
```

如果此时核销已经先成功，并使当前“未履约售后”失效，则必须拒绝基于旧售后创建退款。

---

## 12. pet-coupon-api

### 12.1 CouponQueryApi

```java
public interface CouponQueryApi {

    List<CouponAvailableDTO> listAvailable(
        CouponAvailableQuery query
    );

    CouponInstanceDTO getCoupon(CouponQuery query);
}
```

### 12.2 CouponCommandApi

```java
public interface CouponCommandApi {

    CouponFreezeResult freeze(CouponFreezeCommand command);

    CouponConsumeResult consume(CouponConsumeCommand command);

    CouponReleaseResult release(CouponReleaseCommand command);
}
```

退款成功返券通过 `RefundSucceededEvent` 消费：

```text
FULL REFUND
→ 原券仍在有效规则范围：恢复
→ 剩余有效期 < 24h：延至 refundSucceededAt + 24h

PARTIAL REFUND
→ 不返券
```

V1.0 不存在积分抵扣，因此 coupon contract 不与 points 抵扣混合。

---

## 13. pet-points-api

V1.0 积分仅“赚取 + 余额 + 流水”，无订单抵扣。

### 13.1 PointsQueryApi

```java
public interface PointsQueryApi {

    PointsBalanceDTO getBalance(PointsBalanceQuery query);

    PageResult<PointsLedgerDTO> queryLedger(
        PointsLedgerQuery query
    );
}
```

### 13.2 PointsRewardApi

```java
public interface PointsRewardApi {

    PointsChangeResult reward(PointsRewardCommand command);

    PointsChangeResult clawback(PointsClawbackCommand command);
}
```

订单奖励优先通过事件触发。

退款成功：

```text
FULL
→ 扣回本订单全部奖励积分

PARTIAL
→ rewardPoints × refundRatio
→ 四舍五入
→ 扣回
```

必须基于“本订单奖励流水”扣回，不允许直接按账户余额猜算。

---

## 14. pet-review-api

### 14.1 ReviewQueryApi

```java
public interface ReviewQueryApi {

    ReviewEligibilityDTO checkEligibility(
        ReviewEligibilityQuery query
    );

    ReviewDTO getByOrder(ReviewByOrderQuery query);
}
```

### 14.2 ReviewCommandApi

```java
public interface ReviewCommandApi {

    ReviewCreateResult create(ReviewCreateCommand command);

    ReviewAppealResult appeal(ReviewAppealCommand command);
}
```

评价创建前必须调用 `OrderQueryApi.checkReviewEligibility()`。

评价记录保留：

```java
boolean scoreIncluded;
```

`RefundSucceededEvent(PARTIAL)` 消费逻辑：

```text
若订单已核销：
  已存在评价 → scoreIncluded=false
  尚未评价 → 后续创建时 scoreIncluded=false
```

评价申诉每条最多一次；该机制与售后复审无关。

---

## 15. pet-notification-api

通知模块以事件消费为主，交易模块不应同步拼装站内消息正文。

### 15.1 NotificationApi

```java
public interface NotificationApi {

    NotificationCreateResult createStationNotification(
        StationNotificationCommand command
    );

    NotificationPreferenceDTO getPreference(
        NotificationPreferenceQuery query
    );
}
```

强制站内通知类别：

```text
ORDER
REFUND
VERIFICATION
AFTERSALE
AUDIT
```

这些类别不得因用户关闭外部推送而停止生成站内消息。

外部微信通知通过 Provider Adapter：

```java
public interface ExternalNotificationProvider {
    DeliveryResult send(ExternalNotificationMessage message);
}
```

具体使用公众号、订阅消息或其他微信能力不进入本 Contract，等待账号能力确认。

---

## 16. pet-thirdparty-api

第三方团购与平台订单完全隔离。

```java
public interface ThirdPartyGroupBuyVerificationApi {

    ThirdPartyCouponQueryResult queryCoupon(
        ThirdPartyCouponQuery query
    );

    ThirdPartyVerificationResult verify(
        ThirdPartyVerifyCommand command
    );
}
```

技术边界：

- 不创建 `pet_order`。
- 不创建平台 `payment_order`。
- 不创建平台 `refund_order`。
- 不计入平台 GMV / 平台收入。
- 上游结果为事实来源。
- 需要独立幂等 requestId、渠道流水、查询补偿机制。

---

## 17. Customer Service 边界

V1.0 产品确定“人工客服要做”，但具体承载方式仍未确定。

因此当前只冻结模块边界：

```text
pet-customer-service-api
pet-customer-service-biz
```

暂不冻结供应商/会话协议/API 方法，避免技术文档替产品决定“自建聊天、第三方客服、企微或其他承载方式”。

确定承载方式后再补充该模块 Contract。

---

## 18. 核心事件契约

事件统一采用 Envelope：

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

首批事件：

```text
PaymentSucceededEvent.v1
PaymentFailedEvent.v1
OrderPaidEvent.v1
OrderConfirmedEvent.v1
OrderRejectedEvent.v1
OrderVerifiedEvent.v1
RefundOrderCreatedEvent.v1
RefundSucceededEvent.v1
RefundFailedEvent.v1
AfterSaleCreatedEvent.v1
AfterSaleResolvedEvent.v1
AfterSaleInvalidatedEvent.v1
MerchantDisabledEvent.v1
ReviewCreatedEvent.v1
```

事件消费者必须以：

```text
(event_id, consumer_name)
```

做唯一幂等。

---

## 19. 核心流程调用图

### 19.1 创建订单

```text
C端
↓
order-biz
├─ user-api.getPetSnapshot
├─ merchant-api.checkOrderEligibility
├─ service-api.getServiceSnapshot
├─ schedule-api.hold
├─ coupon-api.freeze（有券时）
├─ 写 pet_order + snapshots
└─ payment-api.createPayment
```

失败补偿：

```text
订单创建失败
→ release schedule hold
→ release coupon freeze
```

不允许依赖跨模块全局事务。

### 19.2 支付成功

```text
拉卡拉回调
↓
payment-biz 本地事务
├─ 更新 payment
└─ outbox: PaymentSucceededEvent
     ↓
order consumer
├─ markPaid
├─ 订单 → PENDING_CONFIRM
├─ confirm schedule reservation
└─ 触发/消费 coupon consume
```

商家确认截止：

```text
confirmDeadline = paidAt + 30min
```

### 19.3 服务开始前退款

```text
refund.apply
↓
order.checkRefundEligibility
↓ AUTO_FULL_BEFORE_SERVICE
refund application AUTO_APPROVED
↓
acquire CREATE_REFUND guard
↓
创建 FULL refund_order
↓
order.markRefundOrderCreated
↓
commit guard
↓
payment-refund-api.submitRefund
```

槽位此时仍不释放。

### 19.4 服务开始后退款

```text
refund.apply
↓
PENDING_MERCHANT
↓
24h
├─ merchant approve → FULL refund_order
├─ merchant reject → 恢复原订单状态
└─ timeout job → AUTO_APPROVED → FULL refund_order
```

### 19.5 售后处理中核销

```text
aftersale = PROCESSING
order = PENDING_SERVICE
refund_order = NONE

商家 verify
↓
acquire VERIFY guard
↓
核销成功
↓
OrderVerifiedEvent
↓
当前未履约型 aftersale INVALIDATED
```

### 19.6 售后退款与核销竞态

```text
线程 A：VERIFY
线程 B：CREATE_REFUND

同一 order guard
↓
只允许一个业务事实先提交成功
```

退款单创建先成功：

```text
核销拒绝
```

核销先成功：

```text
当前未履约售后失效
旧售后不得再创建退款单
```

---

## 20. 事务边界

### 20.1 允许本地强事务

同模块内部：

```text
order + order_status_log + order_operation_guard
refund_order + refund_transaction + outbox
verification_record + verification_attempt + outbox
aftersale_case + aftersale_status_log + outbox
```

### 20.2 禁止依赖跨模块数据库事务

禁止：

```java
@Transactional
orderApi.xxx();
couponApi.xxx();
pointsApi.xxx();
notificationApi.xxx();
// 假设所有远程调用都能一起 rollback
```

正确模式：

```text
本模块事实提交
+
Outbox
↓
可靠事件
↓
其他模块幂等消费
```

### 20.3 必须同步的跨模块能力

以下仍允许同步 API，因为当前操作必须马上知道成功/失败：

```text
预约 hold
退款/核销 operation guard
支付下单
必要资格校验
退款渠道提交
```

---

## 21. 幂等规范

本节按已接受CCR-W2-IDEMP-001同步，完整作用域/摘要/事务/保留与实现门禁见[23号补充§3～9](23-公共接口与幂等契约补充-v0.1.md)。规范生效不代表旧Schema已兼容或服务已实现。

所有写接口必须有 `requestId`。

建议来源：

```text
用户点击提交：客户端生成 UUID
支付回调：channel + channelTradeNo + eventType
退款回调：refundNo + channelRefundNo + eventType
定时任务：jobName + businessId + deadlineVersion
事件消费：eventId + consumerName
```

数据库必须有对应唯一键或幂等表，不仅依赖 Redis。

重复请求处理原则：

```text
相同 requestId + 相同业务参数
→ 返回第一次成功结果

相同 requestId + 不同业务参数
→ IDEMPOTENCY_KEY_CONFLICT
```

上面requestId须在`(commandNamespace, actorType, actorId, authorityScope)`中比较，主体来自可信身份。内部确定性串不强制终端UUID；requestId最多512 UTF-8字节、禁止控制字符、不trim/改大小写。规范参数含写入相关路径/查询/body、用途与敏感业务码，排除追踪/身份传输凭证；旧绑定按旧schema/canonical版本解释，不覆盖摘要。

先无业务副作用的Admission短独立事务绑定RESERVED与参数，再由Execution持记录锁，把本模块业务/首次成功回执/必要后置意图同顶层本地commit；失败整笔回滚业务但保留绑定，同参可重新检查重试、异参仍冲突。RESERVED不是租约，不按TTL删除；成功最终commit后才返回。并发忙有界等待（默认建议2秒）后COMMON_CONFLICT；commit未知查主库原key，不盲重做。

成功重放每次仍检查当前权限、结果资源范围与脱敏，不重跑已消耗的一次性业务执行资格；V1不自动删除去重事实。不同requestId仍受业务unique/CAS/guard约束。五处旧全局request_id及事务表64字符映射未迁移前不宣称可直接接入。Provider同步成功（如createPayment支付参数）不能替为受理回执；未完成23号§7分阶段映射的命令不接入公共成功重放。不扩五字段Context或跨模块大事务。

---

## 22. Feign 迁移规范

未来拆服务时禁止把核心 API Interface 直接改成：

```java
@FeignClient
public interface OrderApi { ... }
```

推荐：

```java
public interface OrderQueryApi {
    OrderSnapshotDTO getOrder(OrderIdQuery query);
}
```

调用方 Adapter：

```java
@Component
public class FeignOrderQueryApiAdapter implements OrderQueryApi {

    private final OrderFeignClient client;

    @Override
    public OrderSnapshotDTO getOrder(OrderIdQuery query) {
        return client.getOrder(query.orderId());
    }
}
```

Feign Client 属于 infrastructure/adapter：

```java
@FeignClient(name = "order-service")
interface OrderFeignClient {
    @GetMapping("/internal/v1/orders/{orderId}")
    ApiResponse<OrderSnapshotDTO> getOrder(
        @PathVariable String orderId
    );
}
```

核心 `*-api` 模块因此不依赖 Spring Cloud。

---

## 23. 编译期依赖约束

Maven 规则：

```text
*-api
→ 仅依赖 pet-common / 必要 contract 模块

*-biz
→ 可依赖自身 api + 其他模块 api

任何 *-biz
→ 禁止依赖其他 *-biz
```

建议后续使用：

```text
Maven Enforcer
+
ArchUnit
```

自动检查：

```text
Repository 不可跨模块访问
DO/Entity 不可出 biz 包
controller 不可直接调用其他模块 repository
biz 间不得直接依赖
```

---

## 24. 本版本未做产品裁决的内容

以下内容只建立技术扩展点，不在本 Contract 擅自确定产品规则：

```text
1. 商家退款拒绝“原因分类”的具体字典值
2. 微信外部通知最终使用公众号、订阅消息还是组合方式
3. 人工客服具体承载方式
4. 黑名单未来具体限制行为
5. 分钟级排期快速生成工具的具体交互
```

---

## 25. 下一步

完成本内部 API Contract 后，继续输出：

```text
1. Error Code Registry
2. Integration Event Catalog
3. HTTP/OpenAPI Controller Contract
4. 定时任务与补偿任务清单
5. 测试矩阵
6. Maven 多模块工程骨架
```
---

## 26. 迟到支付自动退款契约

该场景没有 C 端恢复订单 API，也没有预约重选 API。

前置事实：

```text
order_stage = CANCELED
cancel_reason = PAYMENT_TIMEOUT
payment 渠道确认 SUCCESS
```

order 模块处理 `PaymentSucceededEvent` 时：

```text
不执行正常支付成功履约转移
不调用 schedule.confirm
不发布正常 OrderPaidEvent
```

而是发布：

```text
LatePaymentSucceededAfterTimeoutEvent.v1
```

refund 模块消费后使用已有 `RefundCommandApi.createRefund(...)`：

```text
refundType   = FULL
refundSource = LATE_PAYMENT_TIMEOUT
refundAmount = channelPaidAmount
sourceBizId  = paymentId
```

优惠券：

```text
支付超时关闭时已释放。
迟到支付自动退款不再次恢复、不抢回。
```

预约：

```text
不恢复。
```

积分：

```text
不进入正常 OrderPaidEvent，正常情况下不奖励本单积分。
```

以下旧设计全部废弃：

```text
LatePaymentRecoveryApi
RestoreAfterLatePaymentCommand
LatePaymentReselectAppointmentCommand
late_payment_recovery 表
预约重选恢复流程
```


## 27. AUTH-001已接受语义同步候选

状态：ACCEPTED_MAPPING / SYNC_CANDIDATE_NOT_IMPLEMENTED；尚未合并或实现。来源：已接受2963e391/draft-v2与PR14合并643f05cd，SSOT§24/25及22/24号补充。D1/D2不重新审批。

### 27.1 信任与模块边界

CommandContext仍为requestId/traceId/operatorType/operatorId/source五字段，QueryContext与OperatorType不扩展；source、客户端userId/staffId/workspace/角色名均不授予权限。认证适配器用真实会话解析可信主体，API调用方不能用HTTP自报DTO冒充Principal。pet-user只拥有小程序用户、认证尝试/凭据及会话；pet-admin拥有运营账号、角色/extra授予/scope、Web账号密码凭据/会话及审计。商家成员及申请/签约资格由merchant模块API提供，不能跨Repository。

需要的内部能力仅同步已批准职责：解析当前会话身份；列本人商家/门店成员关系；检查正常/受限/拒绝准入；执行动作或读取旧成功回执时检查当前身份、动作、资源范围和用途。主账号到可执行员工身份、Principal引用、ResourceScope、versions完整结构及新方法签名尚未在原方案逐字段冻结，具体新字段仅放[planning映射附录](../../planning/ccr/AUTH-001/contract-sync-handoff.md)，本节不伪造Java接口定义。

### 27.2 当前授权与并发边界

入口初检后，23号业务Admission及Execution锁规则保持；未成功写入须在取得业务/幂等锁后、首个业务修改前检查当前权威授权。Owner在自己的短只读事务原子读取事实与单调版本；跨Owner收集后按相同顺序复核版本，变化最多重取3次/总2秒，不能稳定则503关闭，版本不能回退或ABA，不借业务RR旧快照/缓存allow降级放行。最终检查完成后发生长等待、失锁或事务重试，必须重新检查；检查到首写预算2秒。相关Owner未提供原子快照/版本能力前保持实现门禁。

撤权先于最终检查的权威观察时点提交则拒绝；检查先通过的短本地事务可以完成，不承诺撤权追溯取消所有在途commit，不跨biz持锁。审计记录实际采用的版本与检查时点。成功回执、敏感数据发送/下载另检查当前身份/动作/结果范围/用途并脱敏；无权401/403/防枚举404不带旧data，依赖失败503。重放不再次检查已消耗的一次性执行业务资格，也不把旧actions当授权。可靠已受理退款等SYSTEM任务按原业务承诺继续，不因原操作员撤权取消渠道退款。

### 27.3 准入及认证专用幂等

MerchantOrderEligibilityDTO及checkOrderEligibility仍只管新订单；OFFLINE禁止新单但保持存量履约/售后，不能把新单资格当全工作台准入。冻结明确读取/处罚申诉保持，冻结核销/退款处理/补证写动作仍待明确。Provider未知绝不当签约成功；客户端phone/userId不生成staff授权。

认证前没有USER主体，AuthAttempt为认证模块专用证明交换与幂等边界，不假冒SYSTEM或扩OperatorType，也不直接复用通用业务执行器。真实证明完成后的会话/账号事实与最小回执由所属Owner原子提交；短期秘密恢复需原attempt秘密或refresh秘密与原key且满足当前身份/期限，永不只凭requestId重放token。具体加密存储/物理Schema/迁移在planning存储提案待审，不因本节而成为已批准DDL。

V1无额外MFA，所有运营角色仅普通主认证；图形captcha/原短信主登录/手机号校验和密码重置证明、RBAC/用途/审计/同人业务确认保留。


## 28. AUTH最小Web内部字段与B1实现映射

本段基于已接受D1/D2、A/B1/C及Admin/QA团队字段复核，只定稿本Owner的Web查询边界。C/M成员、签约、跨Owner ResourceScope与通用versions新结构仍按planning具体待定，不被本节泛化。

- AdminSessionQueryApi.resolveSession(String accessToken)为本地可信适配入口，返回AdminSessionView；原始秘密不进入Context/Principal、日志或异常。返回只是当次权威解析结果，调用者不能构造一个Principal来代替检查。
- AdminSessionPrincipal：audience固定ADMIN_WEB，sessionId/operatorId为正Long十进制String，sessionGeneration为非负long；映射已有CommandContext时operatorType固定PLATFORM_OPERATOR，不增加第六字段。
- AdminDataScope：mode=ALL/CITY/MERCHANT/NONE，cityCodes/merchantIds非null防御复制、去重约束并稳定排序；ALL/NONE空集合，CITY非空≤100且无merchant，MERCHANT非空≤1000且无city；NONE仅计算响应，不授予。词法不证明资源存在。
- AdminPermissionSnapshot：operatorId、authzVersion=epoch:revision、checkedAt毫秒OffsetDateTime、roles(roleId/roleCode/displayName)、dataScope、actionCodes；全部在同一Owner短读事务取得。AdminSessionView包含principal、expiresAt和permissions；HTTP按原CurrentSession或PermissionSnapshot明确投影，不泄露token或更名dataScope。
- 第一切片只有本人主认证/会话/权限自查询，生产可执行领域动作目录为空，仍返回真实角色及范围；超管也不被赋予未实现订单/退款占位动作。

B1秘密回执：以最终成功SQL事务提交前DB时间锚点固定anchor+60秒，commit确认后按剩余TTL不可覆盖发布；缓存I/O后用新事务核当前身份/generation/证明消费/同一resultRef及截止，禁止沿用旧RR快照。缓存/密钥不可用503不重签，已过期/撤销401；窗口不改变Web30分钟idle等原会话期限。

## 商家域已批准同步（2026-09-17）

MerchantQueryApi三查询的参数/DTO、原五字段资格DTO、新成员事实API与普通主账号USER操作者映射，以[27号商家域契约](27-Merchant-Domain-Contract-v0.1.md)§4～5为准。新单资格不替代存量资格。原外部签约Provider依赖被SSOT §26电子协议裁决覆盖；冻结写动作与主账号核销staff映射仍未解除。

## 商家申请审核接口同步候选（2026-09-17）

申请查询/草稿/提交/领取/核验/决定按[30号补充](30-Merchant-Application-Contract-v0.1.md)定义，当前无业务实现。申请业务DTO只由merchant-api拥有；admin-api的最终授权查询必须采用自身通用AdminResourceScope，不依赖merchant-api类型。

AdminResourceScope字段为resourceType、resourceId、merchantId、cityCode、scopeVersion，均为String；对申请资源type固定MERCHANT_APPLICATION、merchantId为服务端reservedMerchantId，cityCode来自已提交revision。AdminActionCheckQuery的sessionId/sessionGeneration/operatorId来自真实AdminSessionPrincipal，actionCode取服务端动作，phase=EXECUTE/READ_RESULT；权限快照不是长期执行许可。每次检查当前会话、动作和scope，依赖失败关闭。此为跨模块兼容增量审阅定义，不注册新生产授权端点。
