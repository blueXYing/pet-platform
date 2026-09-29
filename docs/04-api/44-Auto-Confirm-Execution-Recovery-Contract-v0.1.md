> 2026-09-29: [Contract 46](46-Order-Reschedule-Contract-v0.1.md) supersedes initial-round-only restrictions. Round-1 execution/repair requires immutable ORDER and SCH reschedule proofs. Old-round tasks and repair remain STALE. All switches remain off by default.

# 首轮自动接单执行与恢复契约 v0.1

2026-09-29 用户批准 A 推荐方案与 B 剩余实现；授权原文见[CCR](../../planning/ccr/CCR-W2-API-001/order-auto-confirm-proposal.md)。沿用 SSOT 30 分钟、单次预约、退款及核销硬规则。无新 DDL、公开 HTTP、生产渠道调用或启用。

## 1. 内部命令及错误

`OrderAutoConfirmApi.autoConfirm(AutoConfirmOrderCommand)`：context 为可信 SYSTEM（requestId 精确 `TASK:ORDER_AUTO_CONFIRM:{orderId}:0`，非空 traceId/source），orderId 为正十进制 Snowflake String，expectedConfirmRound 仅0，expectedConfirmDeadline 为毫秒 OffsetDateTime（按 instant 比较）。round1 输入明确 COMMON_INVALID_ARGUMENT，旧0遇当前1返回 STALE。

结果：CONFIRMED、ALREADY_CONFIRMED、STALE、NOT_DUE、BLOCKED_BY_REFUND。同轮不同截止为 IDEMPOTENCY_KEY_CONFLICT；当前事实缺失/损坏、支付对账中、预约漂移或依赖不可读为 COMMON_DEPENDENCY_UNAVAILABLE。只有已合法推进的状态/轮次可 STALE，未知状态不吞成成功。非 SYSTEM 为 COMMON_FORBIDDEN。仅内部组件调用，不相信客户端身份字段。

同一 DataSource 的独立 READ_COMMITTED 事务、15秒总超时与2秒锁等待：定位门店提示 → 门店 guard → ORDER 行及 NORMAL 付款结果 → PAYMENT 当前成功凭证 → SCH CONFIRMED预约 → REFUND当前存在性 → ORDER CAS、状态日志和 Outbox。拒绝嵌套外层事务。DB UTC_TIMESTAMP(3) 决定是否到期，原截止必须等于渠道 paidAt+30min。

付款核对订单/商户/门店/用户、paymentId、successEventId、渠道流水、CNY金额与 paidAt；不信任仅历史付款事件。SCH 按当前订单/门店/预约查询。版本用于最终 CAS，不作为任务轮次。未到期返回 NOT_DUE，预约占用不变。

## 2. REFUND 权威存在性及并发

`RefundOrderFactsApi.findByOrder(orderId,storeId,QueryContext)` 要求调用者已锁定 authoritative ORDER/store，并持同 DataSource 的门店 guard 到提交。REFUND Owner 直接查询自己 refund_order，返回 Fact.refunds：空列表明确 NONE；非空明确 EXISTS，每项 refundOrderId、orderId、status。所有来源、FAILED/UNKNOWN、无 refund_execution 绑定均计入。查询失败抛异常，绝不降级空列表。返回列表只为显式表达事实，不新增多退款业务规则。

ORDER 的 refund_order_id 或 REFUND 的 EXISTS 任一成立即 BLOCKED_BY_REFUND；投影指针不一致登记 REFUND_BINDING_MISMATCH。所有退款创建者必须同店 guard 持锁到提交；空查询本身不提供互斥。当前迟到退款生产者已遵守；普通退款创建入口留后续交付。退款先提交阻断确认；退款回滚不构成永久边界；确认先提交不禁止后续合法服务前退款。

仅存在 current_refund_application_id/current_aftersale_id 且没有退款事实时，APPLICATION_FACTS_UNAVAILABLE 可重试并持久诊断，不能把任务永久成功/取消，也不改变未履约售后与核销规则。

## 3. 原子确认、成功证明和事件

更新 PENDING_CONFIRM → PENDING_SERVICE、confirm_mode=AUTO、confirmed_at=DB UTC、version+1。order_status_log 的 ORDER_AUTO_CONFIRMED 记录稳定 requestId，remark 为原始确认 payload 加 eventId，作为同事务不可变成功证明；字段已存在，无 Schema 变更。重复成功要求唯一匹配证明、同轮/截止/预约/门店及 AUTO确认时间；返回 ALREADY_CONFIRMED，不重复日志/事件。损坏证明不能恢复为新的确认。提交 ACK 丢失可依此重放恢复。

`OrderConfirmedEvent.v1` 使用标准 IntegrationEvent：eventVersion=1，aggregateType=ORDER，aggregateId=orderId，occurredAt=confirmedAt；payload 精确七项：orderId String、reservationId String、storeId String、confirmRound 整数0、confirmMode AUTO、confirmDeadline UTC毫秒OffsetDateTime、confirmedAt UTC毫秒OffsetDateTime。金额/客户信息/渠道报文不输出。消费者后续按 eventId 幂等；本轮没有新增通知消费者。

## 4. B 执行及恢复

任务格式沿用43号。Worker 仅注册 ORDER_AUTO_CONFIRM；校验已领取 lease 与 TASK Owner 提供的原始提交快照，包括任务ID/键/归属/业务ID/类型/expectedVersion NULL/重试策略和上限8/严格三字段 payload/原截止。参数损坏不推进订单，持久 TASK_BINDING_CONFLICT 并重试。

CONFIRMED/ALREADY_CONFIRMED → Success；STALE/BLOCKED_BY_REFUND → Cancelled；NOT_DUE → 原截止后1秒 Retry（DB时钟），其余依赖错误 →30秒 Retry。基础设施最多8次，DEAD 保留。解码异常走 task-core 的 HANDLER_EXCEPTION 重试规则。

`OrderAutoConfirmRepairApi.repairMissingTask(context,orderId)`：SYSTEM requestId 精确 `REPAIR:ORDER_AUTO_CONFIRM:{orderId}:0`；新事务执行同一完整资格校验：
- 缺任务：原任务键、原截止（过去也不顺延）幂等提交，CREATED。
- 相同 READY/RETRY_WAIT/RUNNING：EXISTS；不重置 executeAt、状态、租约、重试及尝试记录。
- 不可变绑定冲突：冲突错误、持久诊断，不删除旧任务或改号。
- 相同 DEAD/CANCELED/SUCCEEDED 且仍符合待确认资格：持久终态异常；未到期 NOT_DUE 留待后续扫描，到期直接执行同一业务确认事务，RECOVERED；保留原任务及 attempts。
- 合法过时为 STALE，退款阻断为 BLOCKED_BY_REFUND。

扫描每60秒按 ORDER 自有待确认订单ID升序取至多100条，逐订单独立事务，完整扫描后游标归0。未来终态会被后续扫描重访。只开 worker 时扫描仅登记终态异常；显式 repair=true 才补建/恢复。只读 dry-run 使用43号 inspection，始终不写。自动恢复只针对上述明确绑定和资格，不猜测修复事实。

## 5. 装配、证据与限制

所有开关默认 false：pet.order.auto-confirm.enabled（任务生产）、worker.enabled、repair.enabled、inspection.enabled。worker/repair 要求 enabled 与 pet.payment.foundation.enabled 且真实依赖齐备，否则启动失败；inspection 独立。未做生产启用。

AUTO_CONFIRM_ANOMALY 写在 ORDER 的 order_status_log，from/to 都为当前阶段，remark 仅 code/round；按订单/requestId/code 去重。正常回滚后的依赖失败在独立短事务登记；业务成功证明和失败诊断分开。数据库整体不可用时诊断也可能失败，仅输出脱敏警告，重试状态仍由 TASK 保留。无新告警表或外部告警路由；外部通知没有配置，不能声称已通知值班人。

本44号切片未交付普通商家接单/拒单。2026-09-29批准的[45号](45-Merchant-Order-Actions-Contract-v0.1.md)追加主账号round0确认/拒单HTTP和对应全额退款，OrderConfirmedEvent.confirmMode扩展MERCHANT。一次改期/round1、完整角色、公开读侧/小程序/消息消费者仍属后续Issue。ORD-002对完整ORD-001的依赖保持，内部切片不代表完整业务DoD。生产发布/合并需单独授权。
