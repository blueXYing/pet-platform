# 迟到支付退款资金链独立审查

## 集成收尾（2026-09-28）

以下保留独立静态审查时的证据。其待验收项已由 `LateRefundAcceptanceTest` 的 16 项真实隔离 MySQL 验收完成，最终集成 `64c26ef` 的指定回归共 50 项、零失败/错误/跳过。覆盖单次提交、确认丢失重放、无权威回执的伪成功拒绝、查询时间下界、成功事件唯一、DEAD/CANCELED 对账及乱序投影。

建议的 success binding 更新影响行数检查已在 `16d34bf` 补齐；渐增查询退避已在 `c8828bb` 落地，并由装配测试验证。无未关闭的阻断审查项。完整仓库 CI 证据见本轮 PR；这不代替真实渠道联调。

## 原始独立审查记录

基线：`13291c8`（2026-09-28）。范围仅为迟到退款的 REFUND/PAYMENT 协作；本次只读生产代码，未调用真实渠道。结论：**静态审查未发现阻断性资金错误**。端到端 SUCCESS/DEAD 结果由 QA 独立验收，本结论不代替该测试。

| 边界 | 证据与结论 |
|---|---|
| 一单一退款、金额与原成功事件 | `LateRefundService.java:74-125` 在同一事务、同一门店 guard 下取 ORDER 的迟到支付事实与 PAYMENT 的真实成功事实，核对原支付事件、交易号、时间和真实金额，再创建业务退款单、绑定、outbox 和 submit task。`42-Late-Refund-Execution-Schema-v0.1.sql:27-33` 对订单、支付、退款号、requestId 和创建事件加唯一约束；`LateRefundService.java:158-205` 对业务退款单和执行绑定的 ID、退款号、订单号、创建时间与金额显式交叉核验。|
| UNKNOWN 不重发、提交或数据库 ACK 丢失 | `PaymentRefundService.java:196-239` 先提交唯一的 `MAY_HAVE_SENT` dispatch，再调用网络；已有 dispatch 的 submit 重放走 `queryExisting` (`:102-108`, `:205-210`)，不再次提交。网络异常转待查 (`:113-119`, `:150-165`)；即使提交前持久化成功、ACK 丢失而请求实际未发，也只查原退款号并最终对账，不冒险重发。|
| 裸 hint 不构成退款成功 | `RefundExecutionService.java:55-59,91-140` 仅以 `VERIFIED_SUCCESS` 提示触发 `finish`，而 `finish` 必须在 guard/事务内读取 `PaymentRefundResultFactsApi.requireVerified` 并逐项核对退款单、订单、支付、门店、原交易号、金额、币种和回执，再原子落 SUCCESS、审计流水与成功事件。`PaymentRefundResultFactsApiImpl.java:29-61` 要求当前 PAYMENT dispatch 为 `VERIFIED_SUCCESS`，且存在匹配的 SUCCESS 回执；查不到权威事实时不会发布成功事件。|
| 成功重放、退款通知先到 | `RefundExecutionService.java:40-46,111-115` 对已成功业务退款核验后直接完成，成功重复不再写事件。`PaymentRefundService.java:205-210,241-265` 对既有 dispatch 的重放/查询不再要求 PAYMENT 当前仍为 PAID，允许自己的 REFUND 通知先到；首次提交前的准入仍要求原支付成功事实 (`:212-220,312-355`)，提交前再次检查撤销 (`:167-194`)。`LateRefundService.java:77-92` 对迟到事件重放核对不可变绑定，不以 PAYMENT 当前 PAID 为前提。|
| 查询最早时间与终止对账 | `PaymentRefundService.java:241-264` 在 `query_not_before` 前不调用渠道；`RefundExecutionService.java:66-88` 保存下次查询时间并以固定任务键只创建一次 query task。`LateRefundConfiguration.java:191-205` 的 handler 延时至少覆盖 PAYMENT 给出的 deadline；渐增 backoff 为 boot 作者的工作中改动，应以最终提交和 QA 验收为准。`RefundExecutionService.java:157-179` 循环扫描非成功退款的 DEAD/CANCELED 任务并创建幂等对账 issue；`:60-65,143-154` 对终态异常保留 UNKNOWN，不将不确定资金结果误标失败。|

待 QA 确认：同一退款号在 ACK 丢失/submit 重放后 `channel.submit` 恰好一次；伪造 SUCCESS hint 而无 PAYMENT 权威回执时不产生 REFUND 成功；QUERY 严格不早于 `query_not_before`；SUCCESS/DEAD 路径分别落唯一成功事件或 OPEN 对账 issue。测试未完成前不宣称端到端通过。

非阻断加固点：`RefundExecutionService.java:124-127` 更新 `refund_execution.success_event_id` 后没有检查影响行数。正常路径在同一 guard 和事务下先校验非 SUCCESS，因此现有状态机不可达 0 行更新；未来如扩展执行入口，建议将该影响行数显式断言为 1，以便绑定异常时更早失败。
