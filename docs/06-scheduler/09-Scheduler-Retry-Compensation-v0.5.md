# 宠物平台 V1.0 Scheduler / Retry / Compensation 设计 v0.5

> 文档定位：定义模块化单体阶段的延迟任务、定时任务、渠道查单、重试、补偿、Outbox 发布与故障恢复机制。  
> 优先级：产品 SSOT > 技术基线 v0.2 > Internal API Contract v0.2 > 本设计 > 具体实现。  
> 本文中的重试间隔属于“技术默认值”，不是新的产品时限；拉卡拉相关频率最终必须受渠道文档、限流和商户配置约束。

---

## 1. 设计目标

V1.0 必须可靠承接以下异步业务事实：

```text
10分钟未支付关闭
10分钟预约临时锁过期
30分钟商家未接单自动接单
24小时商家未处理退款自动全额退款

支付回调丢失 → 主动查单
退款结果 UNKNOWN/回调丢失 → 主动查单

Outbox 发布失败 → 重试
Event Consumer 失败 → 幂等重试

全额退款后的预约释放/优惠券恢复/积分扣回
部分退款后的积分按比例扣回/评价不计分
上述后置动作失败 → 补偿
```

核心要求：

```text
业务截止时间不能只存在于内存 Timer。
服务重启后任务不能丢。
多实例部署不能重复执行出副作用。
任务晚执行可以接受，但不能执行错误业务。
渠道未知不能误判失败。
补偿失败不能反向修改已经成立的支付/退款事实。
```

---

# 2. 总体架构

V1.0 不采用“每个订单创建一个 Quartz Job”的模型。

采用：

```text
业务模块
  │
  ├─ 本地事务写业务事实
  │
  ├─ 写 async_task（需要延迟执行时）
  │
  └─ 写 integration_event_outbox（需要事实广播时）
  │
  ▼
MySQL Durable State
  │
  ├─ AsyncTaskWorker
  ├─ OutboxDispatcher
  ├─ ReconciliationWorker
  └─ CompensationWorker
```

Spring 的 `@Scheduled` 只负责“唤醒 Worker”，**MySQL 才是任务事实来源**。

优势：

```text
1. JVM 重启不丢任务
2. 支持多实例
3. 支持任务租约恢复
4. 易于审计
5. 未来拆微服务时，每个服务保留同一 task-core
6. 未来可将部分延迟任务替换为 MQ Delay，但业务 Handler 契约不需要推倒重写
```

---

# 3. Maven / 包结构

建议增加纯技术基础模块：

```text
pet-task-core
```

它不是业务模块，不暴露产品能力。

```text
pet-task-core
├── AsyncTask
├── AsyncTaskRepository
├── AsyncTaskWorker
├── TaskHandler
├── RetryPolicy
├── TaskLeaseManager
├── TaskMetrics
└── TaskClock
```

业务模块自己实现 Handler：

```text
pet-order-biz
└── task
    ├── PaymentExpireTaskHandler
    └── OrderAutoConfirmTaskHandler

pet-schedule-biz
└── task
    └── ReservationHoldExpireTaskHandler

pet-refund-biz
└── task
    ├── RefundMerchantTimeoutTaskHandler
    └── RefundChannelQueryTaskHandler
```

`pet-task-core` 不允许依赖任何 `*-biz`。

---

# 4. async_task 状态机

```text
READY
  │ claim
  ▼
RUNNING
  ├─ 成功 ─────────────→ SUCCEEDED
  ├─ 已无业务必要 ─────→ CANCELED
  ├─ 可重试异常 ───────→ RETRY_WAIT
  └─ 不可恢复/超过上限 → DEAD

RUNNING lease 超时
  ↓
可被其他 Worker 重新 claim
```

注意：

- “任务执行时发现订单早已由其他路径处理”不是异常。
- 这种 stale task 标记 `CANCELED` 或 `SUCCEEDED + NOOP`。
- 业务幂等仍由业务表状态/CAS/唯一键保证，不能只靠任务状态。

---

# 5. async_task 字段

建议：

```text
id                  BIGINT Snowflake
task_no             BIGINT Snowflake
task_key            VARCHAR(191) UNIQUE
owner_module        VARCHAR(32)
task_type           VARCHAR(64)

biz_type            VARCHAR(32)
biz_id              BIGINT

status              VARCHAR(32)
priority            INT
execute_at          DATETIME(3)

lease_owner         VARCHAR(128)
lease_until         DATETIME(3)

retry_count         INT
max_retry_count     INT
retry_policy        VARCHAR(32)

expected_version    BIGINT NULL
payload_json        JSON NULL

last_result_code    VARCHAR(64) NULL
last_error_code     VARCHAR(64) NULL
last_error_message  VARCHAR(1000) NULL

created_at          DATETIME(3)
updated_at          DATETIME(3)
finished_at         DATETIME(3) NULL
version             BIGINT
```

原则：

```text
payload_json 禁止保存支付密钥、完整券码、身份证、银行卡等敏感信息。
```

---

# 6. task_key 规范

所有确定性延迟任务必须使用稳定 `task_key`：

```text
PAYMENT_EXPIRE:{paymentId}
RESERVATION_HOLD_EXPIRE:{reservationId}
ORDER_AUTO_CONFIRM:{orderId}:{confirmRound}
REFUND_MERCHANT_TIMEOUT:{refundApplicationId}
PAYMENT_CHANNEL_QUERY:{paymentId}
REFUND_CHANNEL_QUERY:{refundOrderId}
NOTIFICATION_DELIVERY_RETRY:{deliveryId}
```

同一个业务事实重复调度：

```text
INSERT task_key UNIQUE
→ 已存在则读取/更新合法的 execute_at
→ 不创建重复任务
```

---

# 7. 多实例抢占

MySQL 8 使用：

```sql
SELECT id
FROM async_task
WHERE (
       status IN ('READY', 'RETRY_WAIT')
       AND execute_at <= NOW(3)
      )
   OR (
       status = 'RUNNING'
       AND lease_until < NOW(3)
      )
ORDER BY priority DESC, execute_at ASC, id ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

同一短事务中：

```text
claim task
↓
status = RUNNING
lease_owner = instanceId
lease_until = now + leaseDuration
COMMIT
```

真正业务执行必须在 claim 事务之后进行，禁止长时间持有数据库锁。

默认技术参数建议：

```text
poll interval      1s
batch size         100
lease              60s
```

这些是技术配置，不是产品规则，必须配置化。

长任务必须支持 lease heartbeat；V1.0 核心交易任务原则上都应保持短任务。

---

# 8. Handler 通用执行模板

```text
claim
↓
读取最新业务事实
↓
判断 task 是否已经 stale
├─ 是 → CANCELED / NOOP
└─ 否
    ↓
执行业务 Command（带确定性 requestId）
    ↓
成功 → SUCCEEDED
失败
├─ NON_RETRYABLE → DEAD/业务 NOOP
├─ RETRYABLE     → RETRY_WAIT
└─ UNKNOWN       → 转入查询/对账路径
```

Task Handler 不得：

```text
直接 UPDATE 其他模块表
直接调用其他模块 Repository
绕过 *-api
```

---

# 9. Retry 分类

## 9.1 NON_RETRYABLE

例如：

```text
ORDER_STATE_NOT_ALLOWED（且确认是 stale task）
REFUND_APPLICATION_ALREADY_PROCESSED
ORDER_ALREADY_VERIFIED（对核销重放）
业务唯一键已存在且参数一致
```

如果代表“业务早已完成”，按幂等成功/NOOP 结束。

真正的数据矛盾进入 `reconciliation_issue`，不能无限重试。

## 9.2 RETRYABLE

例如：

```text
数据库 deadlock
数据库临时连接失败
HTTP connect timeout
HTTP 502/503
渠道限流
短暂 Redis 不可用
```

进入 `RETRY_WAIT`。

## 9.3 UNKNOWN

支付/退款渠道特别处理：

```text
请求已发出
但无法判断渠道是否执行成功
```

**UNKNOWN 不能直接等同 FAILED。**

正确动作：

```text
UNKNOWN
→ 使用原业务单号主动 query
→ 确认渠道事实
→ 再推进本地状态
```

---

# 10. RetryPolicy 技术默认

下面只是工程默认，可通过配置中心修改。

## FAST_INTERNAL

```text
5s
15s
60s
5m
15m
30m
```

适合：

```text
Outbox 本地分发
数据库短暂异常
内部 API 临时故障
```

## CHANNEL_QUERY

```text
15s
30s
2m
5m
10m
30m
之后按配置继续
```

适合：

```text
支付 UNKNOWN
退款 UNKNOWN
```

最终最大查询周期、QPS 必须根据拉卡拉实际接口能力配置。

## COMPENSATION

```text
1m
5m
30m
2h
6h
```

超过自动重试上限：

```text
task = DEAD
+
reconciliation_issue = OPEN
+
技术/运营告警
```

---

# 11. PAYMENT_EXPIRE

任务：

```text
PAYMENT_EXPIRE:{paymentId}
```

触发点：

```text
paymentExpireAt = 订单创建/支付窗口开始 + 10分钟
```

执行：

```text
读取 payment + order
↓
如果已 PAID
→ NOOP

如果 now < paymentExpireAt
→ 重新 execute_at=paymentExpireAt

如果从未真正向渠道发起支付
→ 关闭支付单/待支付订单

如果已向渠道发起且本地仍 PAYING / 结果不确定
→ 先 PaymentApi.queryPayment
    ├─ 渠道确认成功 → 按 PaymentSucceeded 处理
    ├─ 渠道确认失败/关闭 → 关闭本地订单
    └─ 渠道 UNKNOWN → 不释放预约/优惠券，进入 PAYMENT_CHANNEL_QUERY
```

**禁止仅因为本地 10 分钟到了，就把一个渠道可能已支付的订单直接关闭。**

支付最终确认未成功后，订单关闭事实应驱动：

```text
释放 TEMP/CONFIRMED reservation（按当时状态）
释放冻结优惠券
```

建议新增集成事件：

```text
OrderPaymentExpiredEvent.v1
```

而不是 order-biz 直接访问 schedule/coupon Repository。

---

# 12. RESERVATION_HOLD_EXPIRE

任务：

```text
RESERVATION_HOLD_EXPIRE:{reservationId}
```

执行：

```sql
UPDATE schedule_reservation
SET status = 'EXPIRED'
WHERE id = ?
  AND status = 'TEMP_LOCKED'
  AND lock_expire_at <= NOW(3);
```

如果已经：

```text
CONFIRMED
RELEASED
EXPIRED
```

则 NOOP。

无需查询 order 模块即可安全执行。

---

# 13. ORDER_AUTO_CONFIRM

任务：

```text
ORDER_AUTO_CONFIRM:{orderId}:{confirmRound}
```

`confirmRound`：

```text
首次支付确认 = 0
改期一次后 = 1
```

任务 payload 保存：

```text
expectedConfirmDeadline
expectedConfirmRound
```

执行必须 CAS：

```text
订单仍为 PENDING_CONFIRM
confirm_deadline <= now
当前 confirmRound == expectedConfirmRound
```

成功：

```text
PENDING_CONFIRM
→ PENDING_SERVICE
confirmed_at = now
confirmation_type = AUTO
→ OrderConfirmedEvent.v1
```

竞争：

```text
商家 confirm/reject 先成功
→ 自动任务 NOOP

自动任务先成功
→ 商家随后操作返回 409
```

任务失败：

```text
重试自动接单
+
告警
```

**不得降级为“运营正常代接单”。**

---

# 14. 改期与旧自动接单任务

改期成功：

```text
reschedule_count + 1
confirm_deadline = now + 30min
```

必须：

```text
旧 ORDER_AUTO_CONFIRM task → CANCELED
新建 ORDER_AUTO_CONFIRM:{orderId}:1
```

即使旧任务已经被 Worker 拿到，Handler 也会通过：

```text
expectedConfirmRound
expectedConfirmDeadline
```

发现 stale，并 NOOP。

---

# 15. REFUND_MERCHANT_TIMEOUT

任务：

```text
REFUND_MERCHANT_TIMEOUT:{applicationId}
```

截止：

```text
merchant_deadline = refundApplication.createdAt + 24h
```

执行采用可恢复状态机：

```text
读取 refund_application
↓
若 REJECTED / 已产生 refund_order
→ NOOP

若 PENDING_MERCHANT 且 deadline 已过
→ CAS:
   PENDING_MERCHANT → AUTO_APPROVED

↓
再次检查对应 refund_order
├─ 已存在 → NOOP/SUCCESS
└─ 不存在
   → RefundCommandApi.createRefund(FULL)
```

为什么需要“两段可恢复”：

```text
任务可能在 AUTO_APPROVED 成功后宕机，
但还没创建 refund_order。
```

下一次重试必须能从 `AUTO_APPROVED` 继续，而不是永远卡住。

确定性 requestId：

```text
TASK:REFUND_MERCHANT_TIMEOUT:{applicationId}
```

---

# 16. 退款超时与核销关系

这里采用来源感知规则：

```text
退款申请 PENDING_MERCHANT
→ 本身不禁止核销

refund_order 创建成功
→ 禁止之后的核销
```

如果退款申请等待期间核销先发生：

```text
不能用“已经核销”这一字段直接否决所有退款。
```

具体：

```text
商家同意/24h 超时自动全额退款路径
→ 仍可按核销后退款规则继续创建 refund_order

未履约型售后路径
→ 核销先成功会使旧售后 INVALIDATED
→ 旧售后不得再裁决退款
```

这是 `CREATE_REFUND` Handler 必须读取 `refund source` 的原因。

---

# 17. PAYMENT_CHANNEL_QUERY

适用于：

```text
支付请求超时
支付回调丢失
支付状态长期 PAYING
```

任务不创建第二张 payment_order。

始终使用原：

```text
paymentNo
channel merchant order no
```

主动查单。

结果：

```text
SUCCESS
→ payment 本地 PAID
→ PaymentSucceededEvent

FAILED/CLOSED
→ 根据支付窗口及本地状态安全关闭

UNKNOWN
→ retry CHANNEL_QUERY
```

禁止：

```text
UNKNOWN → 直接重新创建一个业务支付单
```

---

# 18. REFUND_CHANNEL_QUERY

适用于：

```text
refund.status = PROCESSING / UNKNOWN
回调长时间未到
```

执行：

```text
PaymentRefundApi.queryRefund(refundNo)
```

结果：

```text
SUCCESS
→ refund.status = SUCCESS
→ RefundSucceededEvent

FAILED
→ refund.status = FAILED
→ 是否允许同一 refund_order 重试提交取决于 Provider Adapter 的幂等能力

UNKNOWN
→ 保持退款中
→ 不释放预约
→ 继续查单
```

**不得在 UNKNOWN 状态创建第二张 refund_order。**

---

# 19. 渠道提交超时原则

支付/退款 HTTP 请求发生 timeout 时：

```text
timeout != failed
```

先记录：

```text
channel_status = UNKNOWN
```

再查询渠道。

只有 Provider Adapter 明确支持：

```text
相同 merchantOrderNo/refundNo 幂等重放
```

才允许原单重提。

这个能力通过：

```java
ChannelCapabilities
```

配置，不散落在 refund/order 业务代码。

---

# 20. OutboxDispatcher

已有：

```text
integration_event_outbox
integration_event_consume_log
```

V1.0 采用 durable dispatcher。

状态：

```text
NEW
PUBLISHING
PUBLISHED
FAILED
```

当前模块化单体：

```text
Outbox row
↓
LocalIntegrationEventDispatcher
↓
注册的 Consumer Handler
```

未来：

```text
Outbox row
↓
MQ Publisher Adapter
↓
Kafka/RocketMQ
```

业务生产者不改。

---

# 21. Outbox 发布语义

本地单体阶段：

```text
一个 Event 可有多个 Consumer
```

每个 Consumer 先尝试：

```sql
INSERT integration_event_consume_log(event_id, consumer_name)
```

但成功标记必须和消费者业务变更处于同一事务。

正确方式：

```text
BEGIN consumer transaction
  检查 consume_log
  执行业务变更
  写 consume_log SUCCESS
COMMIT
```

若某消费者失败：

```text
事件保持可重试
```

下一次重新分发：

```text
已经成功过的 consumer → 幂等跳过
失败 consumer → 再执行
```

只有所有当前注册的必要消费者都成功后，本地 dispatcher 才把 outbox 视为完全完成。

---

# 22. Outbox 卡死恢复

如果：

```text
status = PUBLISHING
lease_until < now
```

允许其他 Worker 接管。

失败：

```text
retry_count + 1
status = FAILED
next_retry_at = backoff
```

不要物理删除 Outbox。

PUBLISHED 数据按运维归档策略长期留痕/归档。

---

# 23. RefundSucceededEvent 后置动作

退款成功是不可逆资金事实。

消费者：

```text
order
schedule
coupon
points
review
notification
```

即使某一个后置模块失败：

```text
refund.status 仍然是 SUCCESS
```

禁止因为：

```text
优惠券恢复失败
积分扣回失败
消息发送失败
```

把退款改回失败。

---

# 24. 全额退款补偿

期望：

```text
order:
  REFUNDED

schedule:
  reservation RELEASED

coupon:
  满足返券规则则恢复
  剩余有效期 <24h → 延至 refundSucceededAt+24h

points:
  扣回本订单全部奖励积分

notification:
  站内退款成功消息存在
```

如果 Consumer 重试仍失败：

```text
RefundRightsReconciliationJob
```

检查实际结果并重新投递原事件/创建补偿任务。

---

# 25. 部分退款补偿

期望：

```text
order:
  PARTIAL_REFUND

coupon:
  不返

points:
  rewardPoints × refundRatio
  四舍五入扣回

review:
  已核销订单 → scoreIncluded=false
  未核销订单 → 不产生评价资格

schedule:
  若仍存在占用，退款最终成功后释放
```

补偿必须以原 `refundOrderId` 为业务幂等源。

---

# 26. 补偿优先级

优先顺序：

```text
L1 原事件 Consumer 自动重试
↓
L2 周期性 Reconciliation 发现缺口，重新驱动原事件
↓
L3 Compensation Task
↓
L4 DEAD + reconciliation_issue + 人工处理
```

不建议一开始就让业务代码到处创建“补偿单”。

---

# 27. Reconciliation Jobs

## 27.1 RefundRightsReconciliationJob

扫描：

```text
refund.status = SUCCESS
AND succeeded_at < now - gracePeriod
```

检查：

```text
order 投影
schedule release
coupon 结果
points clawback
review score exclusion
```

只检查应该存在的事实，不重新计算产品规则。

## 27.2 PaymentReconciliationJob

检查：

```text
本地 PAYING 时间过长
channel_status UNKNOWN
回调缺失
```

通过 `PaymentApi.queryPayment` 查单。

## 27.3 RefundReconciliationJob

检查：

```text
refund PROCESSING/UNKNOWN 时间过长
```

通过 `PaymentRefundApi.queryRefund` 查单。

## 27.4 OutboxReconciliationJob

检查：

```text
NEW 长时间未发布
PUBLISHING lease 过期
FAILED 长时间未重试
```

---

# 28. reconciliation_issue

用于不能自动安全修复的技术异常。

状态：

```text
OPEN
RETRYING
NEED_MANUAL
RESOLVED
IGNORED
```

典型 issue：

```text
PAYMENT_LATE_SUCCESS_AFTER_LOCAL_CLOSE
REFUND_SUCCESS_BUT_ORDER_PROJECTION_MISSING
REFUND_SUCCESS_BUT_SCHEDULE_NOT_RELEASED
REFUND_SUCCESS_BUT_COUPON_NOT_RESTORED
REFUND_SUCCESS_BUT_POINTS_NOT_CLAWED_BACK
OUTBOX_DEAD
EVENT_CONSUMER_DEAD
```

issue 不是新的订单产品状态。

---

# 29. 支付超时关闭后的迟到支付自动退款

产品已裁决：

```text
订单已因支付超时关闭
+
随后渠道确认真实支付成功
→ 不恢复订单
→ 自动全额原路退款
```

处理链路：

```text
PaymentSucceededEvent
↓
order consumer 发现：
order_stage = CANCELED
cancel_reason = PAYMENT_TIMEOUT
↓
不得执行正常 markPaid → PENDING_CONFIRM
不得发布正常 OrderPaidEvent
↓
发布 LatePaymentSucceededAfterTimeoutEvent.v1
↓
refund-biz 幂等消费
↓
创建 refund_order
refund_type = FULL
refund_source = LATE_PAYMENT_TIMEOUT
refund_amount = channelPaidAmount
↓
PaymentRefundApi.submitRefund
↓
PROCESSING / UNKNOWN
↓
渠道最终 SUCCESS
↓
RefundSucceededEvent
↓
订单展示已退款
```

强制规则：

```text
1. 原 order_stage 保持 CANCELED。
2. 不恢复预约，不要求用户重新选时间。
3. 不进入 30 分钟自动接单流程。
4. 不创建第二张订单。
5. 不重新冻结/占用此前已经释放的优惠券。
6. 优惠券即使已被另一笔订单再次使用，也无需处理冲突。
7. 退款金额使用渠道真实 paidAmount。
8. UNKNOWN != FAILED；UNKNOWN 持续查单。
```

确定性退款 requestId：

```text
EVENT:LATE_PAYMENT_AUTO_REFUND:{paymentId}:{orderId}
```

重复事件：

```text
refund_order.order_id UNIQUE
+
requestId 幂等
→ 只能形成一张业务退款单
```

如果自动退款暂时失败：

```text
技术瞬时错误
→ async_task RETRY_WAIT

超过自动重试上限
→ reconciliation_issue = LATE_PAYMENT_AUTO_REFUND_FAILED
→ 告警人工介入继续完成退款
```

## 29.1 优惠券

支付超时关闭时券已经释放。

迟到支付退款成功：

```text
refund_source = LATE_PAYMENT_TIMEOUT
→ coupon consumer NOOP
```

不得再次恢复或抢回优惠券。

## 29.2 积分

迟到支付不产生正常 `OrderPaidEvent`，因此正常情况下不产生本订单下单奖励积分。

退款成功后按实际积分流水幂等检查；没有奖励流水则 NOOP。

## 29.3 预约

支付超时关闭时预约已释放。

迟到支付后：

```text
不恢复 reservation
```

`RefundSucceededEvent` 到 schedule consumer 时，已释放则幂等 NOOP。

---

# 30. 站内通知与外部通知重试

站内强制业务通知：

```text
ORDER
REFUND
VERIFICATION
AFTERSALE
AUDIT
```

先本地持久化 `notification`，这是权威记录。

外部微信：

```text
notification_delivery
```

发送失败可重试，但：

```text
外部通知失败
!=
站内通知失败
!=
交易失败
```

外部通知达到重试上限后记录 delivery FAILED，不反向改变订单。

---

# 31. 自动接单故障策略

如果 30 分钟任务执行失败：

```text
订单继续保持真实 PENDING_CONFIRM
任务自动重试
系统告警
```

禁止：

```text
失败后直接修改数据库绕过状态机
失败后让运营正常代接单作为常规兜底
```

恢复后按 `confirm_deadline <= now` 继续执行自动接单。

---

# 32. 24h 退款超时故障策略

如果调度系统停机 2 小时：

```text
恢复后扫描 merchant_deadline <= now
```

只要退款申请仍是：

```text
PENDING_MERCHANT
```

就执行 `AUTO_APPROVED`。

因此“24 小时”是业务截止条件，不要求 Worker 必须在毫秒级准点运行。

---

# 33. Scheduler 不负责评价/售后资格关窗

评价资格：

```text
now <= verifiedAt + 30d
```

售后资格：

```text
核销后：now <= verifiedAt + 7d
未核销拒绝退款后：now <= appointmentStart + 7d
```

这些优先实时计算，不需要为每个订单创建“关窗任务”。

这样避免海量无必要定时任务。

如果未来需要“即将过期提醒”，再创建独立 notification reminder task，不改变资格事实。

---

# 34. 数据库与 Redis 职责

Redis：

```text
高并发瞬时预约锁
短期防抖/限流
热点缓存
```

MySQL：

```text
订单最终事实
预约持久事实
退款最终事实
async_task
outbox
consume_log
reconciliation_issue
```

**任何必须在重启后恢复的截止任务都不能只存在 Redis TTL。**

---

# 35. 事务边界

创建异步任务时，如果任务属于本模块事实的必然后续：

```text
业务表更新
+
async_task INSERT
+
outbox INSERT
```

应尽量处于同一个本地数据库事务。

例：

```text
order.markPaid
+
ORDER_AUTO_CONFIRM task
+
OrderPaidEvent outbox
```

这样不会出现：

```text
订单已经待确认
但自动接单任务根本没有创建
```

跨模块仍不使用全局事务。

---

# 36. 任务 Handler 接口

```java
public interface TaskHandler<T> {

    String taskType();

    TaskExecutionResult execute(
        TaskExecutionContext context,
        T payload
    );
}
```

结果：

```java
public sealed interface TaskExecutionResult {

    record Success(String resultCode) implements TaskExecutionResult {}

    record Cancelled(String reasonCode) implements TaskExecutionResult {}

    record Retry(
        String errorCode,
        Duration nextDelay
    ) implements TaskExecutionResult {}

    record Dead(
        String errorCode
    ) implements TaskExecutionResult {}
}
```

Handler 必须可重复执行。

---

# 37. 确定性 requestId

任务重试不能每次生成新 requestId。

使用：

```text
TASK:{taskType}:{bizId}:{generation}
```

例：

```text
TASK:ORDER_AUTO_CONFIRM:2019...:0
TASK:ORDER_AUTO_CONFIRM:2019...:1
TASK:REFUND_MERCHANT_TIMEOUT:2088...:0
TASK:REFUND_CHANNEL_QUERY:2099...:0
```

否则同一个任务重试会穿透业务幂等。

---

# 38. 监控指标

至少：

```text
async_task_due_lag_seconds
async_task_running
async_task_retry_wait
async_task_dead_total

outbox_oldest_unpublished_seconds
outbox_failed_total

payment_unknown_count
refund_unknown_count
refund_unknown_oldest_seconds

reconciliation_issue_open
reconciliation_issue_need_manual

auto_confirm_lag_seconds
refund_merchant_timeout_lag_seconds
```

告警必须按“最老滞留时间”而不只看数量。

---

# 39. 日志

统一字段：

```text
traceId
requestId
taskId
taskType
bizType
bizId
eventId
consumerName
channelTradeNo(masked where needed)
resultCode
errorCode
durationMs
```

禁止在日志记录：

```text
完整支付密钥
完整第三方团购券码
身份证/银行卡明文
未脱敏渠道原始 payload
```

---

# 40. 当前需要落库的技术表

新增：

```text
async_task
async_task_attempt
reconciliation_issue
```

已有：

```text
integration_event_outbox
integration_event_consume_log
payment_transaction
refund_transaction
notification_delivery
```

---

# 41. 实施顺序

```text
1. 建 async_task / attempt / reconciliation_issue
2. 实现 pet-task-core
3. 实现 Task claim + lease + recovery
4. 实现 ORDER_AUTO_CONFIRM
5. 实现 PAYMENT_EXPIRE / RESERVATION_HOLD_EXPIRE
6. 实现 REFUND_MERCHANT_TIMEOUT
7. 实现 PAYMENT/REFUND CHANNEL_QUERY
8. 实现 OutboxDispatcher
9. 实现 Consumer 幂等
10. 实现 Reconciliation
11. 接监控告警
12. 再进入全链路测试矩阵
```
