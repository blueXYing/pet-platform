# 支付中心收款契约 v0.1（CCR-PAY-CENTER-001 阶段 1）

状态：APPROVED（2026-11-05 技术契约审核人 @blueXYing 批准，含 §10 实现补充）。依据 [40 号裁决补充](../01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md)（APPROVED）、
[CCR-PAY-CENTER-001](../../planning/ccr/CCR-PAY-CENTER-001.md)（APPROVED）§3/§4.1/§6/§10/§12 第 1 阶段，补充 23、40、41 号契约。
Schema：[SQL75](../03-database/75-Payment-Center-Schema-v0.1.sql)；DEMO 测试表：[SQL75-Demo](../03-database/75-Payment-Center-Demo-Schema-v0.1.sql)。
本契约不授权 PR 合并、生产迁移、生产启用或真实资金交易。所有新增开关默认 false。

## 1. 范围

本阶段交付：

1. 收款补齐：预下单上送 `settle_type`；已验签通知与查单解析 `log_no`/`log_date` 并持久化；收款商户改为平台唯一商户（可配置，向后兼容）。
2. 业务无关的内部 `PaymentCenterApi`（create / query）与 `PaymentCenterFactsApi`（受锁权威事实）。
3. 测试专用 `bizType=DEMO` 业务方，跑通"支付 → 通知/查单 → 确认"。

本阶段不做：结算单、分账、回退、接收方、钱包、提现、对账（阶段 2～6）；ORDER 迁移到支付中心（阶段 8）；
公开支付回调 HTTP、公开支付中心 HTTP（见 §8 待裁决）；MQ 延迟队列。

## 2. 与现有 ORDER 路径的关系

- ORDER 支付继续使用 `PaymentPreparationApi` / `PaymentInitiationApi` / `PaymentExpiryCoordinationApi`（40、41 号），调用关系、店 guard、到期协调不变。
- ORDER 路径仅有以下收款补齐变化：`payment_order.settle_type` 在 prepare 时按部署配置写入并在预下单原样上送；成功时写入 `log_no`/`log_date`；
  商户号来源可切换为平台唯一商户。ORDER 行的 `biz_type`/`biz_no` 阶段 1 保持 NULL（阶段 8 再定）。
- 支付中心行：`order_id`、`store_id`、`merchant_id` 为 NULL，`biz_type`/`biz_no` 非空。ORDER 的读写入口遇到支付中心行一律失败关闭（503），不会把它当订单支付。
- 支付中心成功事件使用新事件类型（§6），不复用 `PaymentSucceededEvent.v1`，避免 ORDER 消费者收到无 `orderId` 的事件。

## 3. 渠道字段

| 字段 | 方向 | 规则 | 依据 |
|---|---|---|---|
| `settle_type` | 1075 预下单 req_data 顶层（`notify_url` 之后） | 行值 `1` 时上送 `"1"`；行值 `0` 或 NULL 时不上送（等同常规结算，保持现有报文字节） | SDK `V3LabsTransPreorderRequest` 注释："0或者空，常规结算方式，如需接拉卡拉分账需传1" |
| `log_no` | 1112 通知 / 1111 查单 resp_data | 可选；1..32 位字母数字，否则视为缺失 | 字段名**待联调核实**：本地无 1111/1112 文档，SDK 无响应类；名称取自 1293/2396 请求同名字段"拉卡拉对账单流水号" |
| `log_date` | 同上 | 可选；`yyyyMMdd` 严格解析，否则视为缺失；与 `log_no` 成对，任一缺失则两者都按缺失处理 | 同上；1293 "posp日期，yyyyMMdd，查清结算用" |

- `log_no`/`log_date` 不参与支付成功判定。缺失或格式不符不得阻断 PAID，也不得从 `trade_time`、`trade_req_date` 推导。
- 首次成功写入；已 PAID 且两列为空时，后续已验签成功回执可补写一次；已有值与新值不同则不覆盖。
- 阶段 2 分账遇 NULL 必须失败关闭并进入人工处理，不猜测。

## 4. 内部 API

全部位于 `pet-payment-api`，仅进程内调用；消费者（终端用户）无法指定收款方、金额、最终状态或密钥。

### 4.1 `PaymentCenterApi`

```java
public interface PaymentCenterApi {
    CenterPayment create(CreateCenterPaymentCommand command);
    CenterPaymentView query(QueryCenterPayment query);
}
record CreateCenterPaymentCommand(CommandContext context, String bizType, String bizNo,
        BigDecimal amount, OffsetDateTime expireAt) {}
record CenterPayment(String paymentId, String paymentNo, String bizType, String bizNo,
        String channel, WechatPayParameters wechatPayParameters, OffsetDateTime parameterValidUntil, boolean replayed) {}
record QueryCenterPayment(QueryContext context, String bizType, String bizNo) {}
record CenterPaymentView(String paymentId, String paymentNo, String bizType, String bizNo,
        BigDecimal amount, String currency, String status, BigDecimal paidAmount,
        OffsetDateTime paidAt, OffsetDateTime expireAt) {}
```

入参规则：

| 项 | 规则 |
|---|---|
| 调用方 | 业务 Owner 模块（进程内可信）。`amount`/`expireAt` 由业务 Owner 从自身权威事实给出，不得来自终端请求体 |
| payer | 仅取 `context.operatorType()==USER` 的 `operatorId`；`openId` 由 USER Owner 的支付身份接口按配置的小程序 AppID 读取，不接受调用方传入 |
| 收款方 | 平台唯一收单商户（部署配置 `merchant_no`/`term_no`/`sub_appid`），命令中不存在该字段 |
| `bizType` | 必须在已启用的业务类型策略表中；阶段 1 仅 `DEMO`，且仅在 DEMO 开关开启时 |
| `bizNo` | `[A-Za-z0-9_-]{1,64}` |
| `amount` | > 0，scale ≤ 2，不超过该 bizType 策略上限 |
| `expireAt` | 毫秒精度；当前时间 + 1 分钟 ≤ expireAt ≤ 当前时间 + bizType 策略上限 |
| `requestId` | 23 号规则；`context.requestId()` |

`create` 成功必须返回已验签且仍有效的微信参数（与 41 号一致，不以"受理成功"替代）。

`query` 允许 USER（仅本人支付）与 SYSTEM，读当前已提交事实，不调渠道，不作为成功证明。

### 4.2 `PaymentCenterFactsApi`

```java
public interface PaymentCenterFactsApi {
    CenterPaymentSuccessFact requireSucceeded(String bizType, String bizNo, QueryContext context);
}
record CenterPaymentSuccessFact(String paymentId, String paymentNo, String bizType, String bizNo,
        String userId, BigDecimal amount, BigDecimal paidAmount, OffsetDateTime paidAt,
        String channelTradeNo, String successEventId, String currency) {}
```

调用方必须在同一 DataSource 的事务内调用；实现锁定 PAYMENT 权威行并核对唯一成功回执，否则 503。事件本身不是成功证明。

## 5. 状态与幂等

### 5.1 状态

| 对象 | 状态 |
|---|---|
| `payment_order.status` | 沿用 `INIT → PAYING → PAID / FAILED / CLOSED`；退款/撤销类回执 → `dispatch_state=RECONCILIATION_REQUIRED` |
| `payment_dispatch.state` | 沿用 41 号 `PREPARED → MAY_HAVE_SENT → PARAMETERS_READY / UNKNOWN`；阶段 1 支付中心不关单 |
| `payment_demo_order.status` | `CREATED → PAID`；实付 ≠ 应付 → `AMOUNT_MISMATCH`（不确认） |

迟到 SUCCESS（行已 FAILED/CLOSED）仍按 40 号记为 PAID 并产生一次成功事件；是否履约由业务 Owner 决定（DEMO 按 §7 确认）。

### 5.2 幂等键

| 场景 | 键 | 冲突处理 |
|---|---|---|
| create 请求 | `payment_center_request.request_key` = `payment.center.create/USER/{operatorId}/{requestId}`，绑定 bizType/bizNo/userId/amount/expireAt | 同键异参 `IDEMPOTENCY_KEY_CONFLICT` |
| 业务对象 | `payment_order.uk_payment_biz(biz_type,biz_no)`：每个业务对象最多一条支付 | 新 requestId 同参数 → 重放原支付；金额/payer/期限不同 → `COMMON_CONFLICT` |
| 查单任务 | `async_task.task_key` = `PAYCENTER_CHANNEL_QUERY:{paymentId}`（09 号 §6 既有规范） | 重复登记不产生第二个任务 |
| 回执 | `payment_channel_receipt(payment_id, receipt_sha256)` | 重复通知/查单只重放 |
| 成功事件 | `payment_order.success_event_id` 唯一 | 通知与查单竞争只产生一次事件 |
| DEMO 发起 | `payment_demo_order.request_key` = `payment.demo.start/USER/{operatorId}/{requestId}` | 同键异参 `IDEMPOTENCY_KEY_CONFLICT` |
| DEMO 确认 | Outbox 消费日志 + `payment_demo_order.source_event_id` 唯一 | 重复投递 NOOP |

### 5.3 发送纪律与查询任务

1. 每次渠道预下单前，在同一事务内：锁支付行与派发行 → 记录 `MAY_HAVE_SENT`（原渠道请求时间/日期、期限、身份摘要）→
   用 `JdbcAsyncTaskSubmitter.enqueueAt` 登记 `PAYCENTER_CHANNEL_QUERY:{paymentId}`（ownerModule=PAYMENT，availableAt=now+5s）。提交后才在事务外请求渠道。
2. 结果未知只用原 paymentNo 查询，不换号、不重发预下单；超时不判失败。
3. 查询任务退避 5s/15s/30s/1m/2m/5m（之后保持 5m），直到渠道终态（SUCCESS/FAIL/CLOSE）或 `expireAt + 5 分钟`；
   到达上限仍非终态 → 任务 DEAD（错误码 `PAYMENT_QUERY_EXHAUSTED`），留待阶段 6 补偿扫描/人工处理，不推定结果。
4. 查询结果只经包内受信适配器交给支付中心回执写入器，与通知走同一锁与去重逻辑。

### 5.4 失败与未知

| 情况 | 处理 |
|---|---|
| 依赖或开关缺失、凭证缺失 | 启动失败或 503，失败关闭 |
| DB 提交结果不明（SQLState 08） | 仅用原绑定在主库事务重读一次；仍不明 → 503，要求原 requestId 重试 |
| 预下单网络/验签/协议异常 | 派发置 UNKNOWN，503；查询任务继续确认 |
| 参数已过期或密文不可读 | 不再预下单；503。业务方需换新业务单号 |
| 通知验签失败 | 不改状态，返回失败（渠道重推） |
| 实付 ≠ 应付 | 记录真实实付；成功事件携带实付；业务 Owner 自行判定（DEMO 记 AMOUNT_MISMATCH） |

## 6. 事件

新增 `PaymentCenterSucceededEvent.v1`（待登记 08 号事件目录）：

```java
public record PaymentCenterSucceededPayload(String paymentOrderId, String paymentNo, String bizType,
        String bizNo, String channelTradeNo, BigDecimal paidAmount, OffsetDateTime paidAt) {}
```

envelope：eventVersion=1、aggregateType=PAYMENT、aggregateId=paymentId、occurredAt=DB 当前时间。与 PAID 状态、回执、`success_event_id` 同事务经 Transactional Outbox 发布。
payload 不含 openid、商户号、`log_no`/`log_date`、原始报文或签名。消费者按 `bizType` 过滤，只处理自己的类型；收到后须用 §4.2 读取权威事实。

## 7. DEMO 业务方（测试专用）

- 代码与表位于 `pet-payment-biz` 的 `center.demo` 包与 `payment_demo_order`；不进入生产迁移，不注册公开 HTTP。
- `DemoPaymentService.start(context USER, amount)`：创建 demo 单（`demo_no` = Snowflake）并调用 `PaymentCenterApi.create(DEMO, demoNo, amount, now+10min)`。
- `DemoPaymentConfirmConsumer` 订阅 `PaymentCenterSucceededEvent.v1`，仅处理 `bizType=DEMO`；在消费日志同事务内调用 `requireSucceeded`，实付 = 应付 → PAID。
- DEMO 策略：期限 10 分钟；金额上限默认 0.01 元（`pet.payment.center.demo.max-amount`）。
- `pet.payment.center.demo.enabled=true` 且拉卡拉环境为 PRODUCTION 时启动失败。

## 8. 开关与配置

| 开关 | 默认 | 作用 | 依赖 |
|---|---|---|---|
| `pet.payment.center.enabled` | false | 装配 `PaymentCenterApi`/`PaymentCenterFactsApi`/回执写入器 | [硬] foundation + dispatch + 平台商户配置 + 渠道时区 + 参数密钥 + USER 支付身份接口 |
| `pet.payment.center.worker.enabled` | false | `PAYCENTER_CHANNEL_QUERY` worker | [硬] center.enabled + async_task |
| `pet.payment.center.demo.enabled` | false | DEMO 业务类型与确认消费者 | [硬] center.enabled + outbox；`pet.payment.dispatch.environment` 须为 `SIT`（见 §10 第 5 条） |
| `pet.payment.lakala.settle-type` | `0` | prepare 时写入行的 settle_type（`0`/`1`）；ORDER 与支付中心共用。阶段 2 分账能力落地前生产不得设 `1`；SIT 联调时显式设 `1`（PLAN §9 Q2 待裁决） | 值域外启动失败 |
| `pet.payment.lakala.platform.*` | 空 | 平台唯一收单商户 merchant-no/term-no/sub-app-id | 与 `stores` 同时配置时启动失败 |

公开支付回调 HTTP、支付中心 HTTP、DEMO 触发 HTTP 本阶段均不注册（是否开放见 PLAN 待裁决项）。

`pet.payment.dispatch.environment`（`LAKALA_ENV`）无缺省值，空值在 dispatch 开启时启动失败。

部署顺序：`PaymentFoundationMapper` 的列清单含 SQL75 新增的 `biz_type,biz_no,settle_type,log_no,log_date`，ORDER 支付读写同样依赖这些列。应用上线前须先在数据库执行 SQL75，否则 ORDER 支付查询报未知列。

## 9. 验收

覆盖：settle_type 上送与历史行不上送、log 字段解析/缺失/格式不符/补写一次/不覆盖、平台商户模式与歧义配置、
create 幂等与冲突、MAY_HAVE_SENT 与查询任务同事务、未知只查不重发、通知 × 查询只一次事件、迟到成功、DEMO 确认与金额不符、
ORDER 路径回归不变、ARCH-001～005 与 `check-persistence-style.py`。需要 MySQL 的测试须在具备隔离库的环境执行，未运行不得声称通过。

## 10. 实现补充（WP-C，2026-11-04；2026-11-05 随本契约批准）

以下是实现时落定、上文未写明的细节。不改变上文任何规则。

1. **USER 支付身份接口**（PLAN §9 Q4 按方案 A 实施）：`PaymentIdentityApi` 新增无门店重载
   `PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId, QueryContext context)`。
   接口默认实现返回 503（失败关闭），`PaymentIdentityApiImpl` 覆盖实现：账号 ACTIVE、唯一 `WECHAT_MINI` 身份等校验与门店版相同，
   只是不要求门店 guard，改为要求调用方在同一 DataSource 的事务内（锁 USER 行）。门店版签名与行为不变。
2. **业务对象已终结**：同一 `(bizType, bizNo)` 的支付已是 PAID/FAILED/CLOSED 时，`create` 返回 `COMMON_CONFLICT`（不再下单，也不是可重试的 503）；业务方需换新单号。
3. **`CenterPayment.replayed`**：`true` 表示参数取自此前已保存的预下单结果（本次未请求渠道）；`false` 表示本次调用完成了预下单。
4. **查询任务结果码**（async_task 内部码，不是 API 错误码，不进 12 号登记）：
   终态 → SUCCEEDED `PAYMENT_CHANNEL_TERMINAL`；非终态 → RETRY `PAYMENT_QUERY_PENDING`；超过 expireAt+5min → DEAD `PAYMENT_QUERY_EXHAUSTED`；
   行进入 `RECONCILIATION_REQUIRED` → DEAD `PAYMENT_RECONCILIATION_REQUIRED`；ORDER 行 → CANCELED `NOT_PAYMENT_CENTER`；从未进入 MAY_HAVE_SENT → CANCELED `PAYMENT_NOT_SENT`。
   task payload 精确为 `{"paymentId":"<id>"}`，ownerModule/bizType=PAYMENT，retryPolicy=`PAYCENTER_CHANNEL_QUERY`，maxRetryCount=60。
5. **DEMO 环境门禁**：`pet.payment.center.demo.enabled=true` 时，`pet.payment.dispatch.environment` 不是 `SIT`（含空值）一律启动失败，比 §7 的"PRODUCTION 时失败"更严。
6. **DEMO 截止时间**：`expireAt = payment_demo_order.created_at + 10 分钟`，从已存行推导，使同一 requestId 重放时绑定参数一致。
7. **参数加密 AAD**：`PAYMENT_CENTER_PARAMETERS_V1:{paymentId}:{paymentNo}`；身份 HMAC 使用独立派生域 `PAYMENT_CENTER_IDENTITY_KEY_V1`，与 ORDER 不互通。
8. **回调路由**：`PaymentCallbackRouter` 按 `payment_order` 行类型分流（`biz_type` 非空且 `order_id` 为空 → 支付中心写入器；`biz_type` 为空且 `order_id` 非空 → 现有 ORDER `PaymentNotificationService`；其他 503）。仍不注册 HTTP。
9. **事件 payload 序列化**：`paidAmount` 为 JSON 数字，`paidAt` 为毫秒精度 ISO-8601 UTC 字符串（如 `2026-11-04T08:00:00.000Z`）。
