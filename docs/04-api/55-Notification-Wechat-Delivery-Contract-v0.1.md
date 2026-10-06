# 通知微信外投 Adapter 与重试骨架契约 v0.1（内部 SPI/任务模型，默认关闭）

NTF-002 交付：站内已落库业务通知经微信通道外投的 Adapter SPI、durable 投递任务、退避重试与 DEAD/FAILED 终态骨架。**V1 不做真实模板配置、不接真实微信 SDK、不配置真实凭据、不发起真实外部调用**；生产启用（真实订阅消息/公众号通道、模板口径、凭据）另行批准。本契约为模块内部 SPI 与任务模型契约，无新 HTTP/OpenAPI 面；Schema 零新增（使用 SQL06 §11 `notification_delivery` 与 SQL13 `async_task`/`async_task_attempt` 既有表）。

依据（原文见文档）：
- SSOT《01-SSOT-宠物平台V1.0-最终业务基线》§16.3 外部微信通知：站内通知+微信侧外部通知；「站内消息 = 权威通知记录」「微信通知 = 外部提醒渠道」；公众号/订阅消息接入形态按实际微信账号能力另行落技术方案（§21 待确认项 3）。§16.4：用户只能关闭外部推送，不能关闭站内业务通知。
- Scheduler《09-Scheduler-Retry-Compensation-v0.5》§30：`notification_delivery`；外部通知失败≠站内通知失败≠交易失败；外部通知达到重试上限后 delivery=FAILED，不反向改变订单。
- 测试矩阵《14-全链路测试矩阵-v0.1》MSG-003（外部微信失败不影响交易，执行 delivery 重试，订单/退款状态不回滚）、TASK-007（达到重试上限 delivery=FAILED/告警；订单保持正确）。
- ISSUE_CATALOG NTF-002（P1，依赖 NTF-001+PLAT-004，scope `backend/pet-notification-*`，映射 MSG-003/TASK-007）。

## 1. 通道与存储模型

- 通道枚举沿用 SQL06 §11 `notification_delivery.channel`：`WECHAT_SUBSCRIBE`（订阅消息）/`WECHAT_OA`（公众号）。V1 骨架生产者装配单通道，默认 `WECHAT_SUBSCRIBE`，可配 `pet.notification.delivery.channel`，非法值拒绝启动。
- 每条站内通知每通道至多一行 delivery（`uk_notification_channel`），状态机：`PENDING → SENT | FAILED | SKIPPED`。`PENDING` 表示已有 durable 任务待投递；`SENT` 携带 `provider_message_id`/`sent_at`；`FAILED` 为重试耗尽或永久失败终态（`last_error` 存技术错误码，1..64 字符，不存原文报文/用户内容）；`SKIPPED` 为终态跳过（如通道未配置、用户偏好关闭——偏好联动语义见 §6 待裁决）。
- `notification` 行是唯一权威记录；delivery 行/任务失败不回滚、不改写任何交易事实（MSG-003）。

## 2. Producer（与站内落库同事务）

- 挂载点：既有消费者 `MerchantApplicationReviewedConsumer`/`ServiceReviewedConsumer` 的 `recordOnce` 事务钩子（新增可选 `afterInboxInsert` 回调）。钩子在消费 guard 声明成功、站内行插入成功后、同一 `REQUIRES_NEW` 通知事务内执行：INSERT `notification_delivery`（PENDING）+ `JdbcAsyncTaskSubmitter.enqueue` 写 SQL13 任务。三者原子提交，任一失败整体回滚（消费可安全重放）。
- 任务模型：

| 字段 | 值 |
|---|---|
| task_key | `WECHAT_DELIVER:{notificationId}:{channel}` |
| owner_module / biz_type | NOTIFICATION / NOTIFICATION |
| task_type / retry_policy | `WECHAT_DELIVER` / `WECHAT_DELIVER` |
| biz_id | 站内 notification 内部 BIGINT ID；payload JSON 中用 String |
| expected_version | 0（投递无行级乐观锁语义，仅透传） |
| payload_json | 严格三字段：`notificationId`/`receiverUserId` 十进制 String、`channel` 枚举；严格解码（重复字段、未知字段、多余字段、尾随文档均拒绝） |
| max_retry_count | 可配，默认 5（0..1000） |
| retry_policy | `WECHAT_DELIVER`，退避阶梯 10s/30s/2m/10m/30m（ handler 重试与 worker 技术异常共用同一命名策略） |

- requestId 确定式：`TASK:WECHAT_DELIVER:{notificationId}:{channel}:0`，与 attempt/worker/version 无关；同时作为 SPI `dedupKey` 供真实通道侧幂等。
- 默认关闭：`pet.notification.delivery.enabled` 未开时无 producer bean，两个消费者的行为与历史版本逐字节一致（显式测试断言零 delivery/零任务）。

## 3. Delivery Adapter SPI（模块内部端口）

`com.petplatform.notification.biz.delivery.spi.WechatDeliveryAdapter`：

```text
deliver(WechatDeliveryRequest) -> WechatDeliveryOutcome
```

- Request：notificationId、channel、receiverUserId、title、content、bizType、bizId、dedupKey——全部取自权威站内行，严格构造校验。
- Outcome（sealed）：`Sent(providerMessageId≤128)` / `RetryableFailure(errorCode≤64)` / `PermanentFailure(errorCode≤64)` / `Skipped(reasonCode≤64)`。实现不得为通道侧结果抛异常（防御性兜底映射为 `WECHAT_ADAPTER_UNEXPECTED` 可重试）；不得无界阻塞；不得记录原始报文。
- V1 装配：`UnconfiguredWechatDeliveryAdapter` 空壳（无 SDK/凭据/网络），每次调用终态 `Skipped("WECHAT_CHANNEL_UNCONFIGURED")`，不消耗重试预算。测试用 `ScriptedWechatDeliveryAdapter` 受控 fake（仅测试域）。
- 真实适配器（订阅消息/公众号 SDK、access_token 管理、模板/跳转参数、频控）另行批准后以独立 bean 绑定（`@ConditionalOnMissingBean` 已预留）。

## 4. Handler（一次尝试语义）

- 绑定核验：按 payload 的 notificationId+receiverUserId 反查权威行（`receiver_type='USER'` 强制），缺失→`Dead("NOTIFICATION_MISSING")`；delivery 行缺失→`Dead("DELIVERY_ROW_MISSING")`；行已 `SENT/FAILED/SKIPPED`→`Success("NOOP")`（终态永不复活，重放安全）。
- 结果映射：
  - `Sent` → CAS `PENDING→SENT`（provider_message_id、sent_at）→ `Success("WECHAT_DELIVERED")`；
  - `Skipped` → CAS `PENDING→SKIPPED`（last_error=reason）→ `Success("WECHAT_DELIVERY_SKIPPED")`；
  - `PermanentFailure` → CAS `PENDING→FAILED` → `Dead(errorCode)`；
  - `RetryableFailure` 且预算未尽（retryCount<maxRetryCount）→ `PENDING` 不变、retry_count=attempt 次数、last_error=code → `Retry(code, 阶梯下一档)`；
  - `RetryableFailure` 且处于最后预算次数（retryCount≥maxRetryCount）→ handler 主动 CAS `PENDING→FAILED` 并返回 `Dead`，保证任务 DEAD 与 delivery FAILED 同次尝试内一致（TASK-007），不留「任务已 DEAD、行仍 PENDING」漂移窗口。
- 至少一次语义：adapter 成功后行更新失败（崩溃/租约丢失）会重试并再次调用 adapter；骨架按至少一次投递建模，真实通道去重由实现按 `dedupKey` 承担（登记为启用前验收项）。
- Handler 未捕获异常由 worker 记 `HANDLER_EXCEPTION` 并按同一退避策略重试；该路径预算耗尽的任务 DEAD 时 delivery 行可能停留 PENDING，属骨架已登记边界（见 §6），由后续对账/告警切片收口。

## 5. 装配与开关

- `pet.notification.delivery.enabled=false`（默认）：本配置类整体不装配，零 bean、零行为。
- `true`：producer bean 注入两个既有消费者（`pet.outbox.enabled`+各自通知开关仍独立生效）、任务 registration、共享 SQL13 worker（`@ConditionalOnMissingBean(AsyncTaskWorker.class)`，与私有材料运行时同款协作先例）。开启另要求：PLAT-002 SnowflakeIdGenerator 生产 bean、SQL06 §11 与 SQL13 表已存在——本切片不执行、不暗示任何迁移。

## 6. 边界与待裁决

- 不做：真实模板/订阅消息授权口径、access_token 与凭据管理、频控、用户偏好（`notification_preference.external_push_enabled`）与外投的联动判定、外投与已读联动、对账扫描/告警生产闭环（`reconciliation_issue` 无写入方）。以上均登记 PR「阻塞与待裁决」。
- HTTP/OpenAPI/事件契约零变化；无新表、无迁移文件；生产 SQL 全部在各 Owner Mapper XML。
- MySQL 验收覆盖：原子提交与回滚收敛、重放幂等、默认关闭零行为（显式断言）、outcome→行状态全矩阵、终态不复活、退避阶梯、最后预算次 DEAD→FAILED、dedupKey 跨尝试稳定、严格 payload 解码绑定。
