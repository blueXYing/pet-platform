# CCR-ADM-DISPOSAL-001：运营处置动作（渠道查单/重试、异常关单、商家下线，63号 v0.2）

状态：PROPOSED / IMPLEMENTED_LOCAL_VERIFIED（本工作树交付，等待用户审阅；未授权合并或生产部署）。授权来源：用户 2026-10-15 拍板"可以开"（[33号裁决补充](../../docs/01-prd/33-运营处置动作授权裁决补充-v1.0.md)），仅授权开启既有 HTTP10 §5.2/§5.5/§5.6 草案能力的实现；本 CCR 只登记实现口径的契约缺口裁决，不新增产品规则。

## 问题与已有依据

PR #166（63号 v0.1）交付运营交易查询面时披露：§5.2 渠道查单/重试、§5.5 异常关单、§5.6 商家下线仅存草案。既有依据：

- HTTP10 §5.2/§5.5/§5.6：路径与固定语义草案（retry 只重试渠道动作不创建第二张业务退款单；异常关闭+全额退款、高权限/二次确认/完整审计、非代接单；商家下线立即停新单、存量继续履约、在途不拒绝下线）。
- SSOT §4（渠道查询/重试不算第二次业务退款）、§15（商家下线规则）、§24（单运营 RBAC）、§22（迟到支付渠道退款原路全额）。
- AUTH-001 提案矩阵第 4/7/8 行动作码：`order.abnormal-close`、`refund.read/refund.channel-query/refund.retry`、`merchant.freeze/merchant.disable/merchant.unfreeze`（本次仅 `merchant.disable` 获授权实现）。
- 51号售后裁决（部分退款仅运营售后裁决，SSOT 封板不变）；63号 v0.1 读侧（ALL 数据范围、防枚举、信封/开关纪律）。

缺 Contract：三条草案的请求/回执字段、错误映射、动作码部署拼写、任务族/退款来源扩展、审计与幂等绑定载体、装配开关组合。按 AGENTS.md「Contract 缺失走 CCR」建本 CCR，实现修订 63号至 v0.2（不占用 64号预分配号）。

## 最小变更提案（不新增产品规则）

1. **路由与开关**：按 HTTP10 草案路径实现 `POST /api/v1/admin/refunds/{refundId}/channel-query|retry`、`POST /api/v1/admin/orders/{orderId}/abnormal-close`、`POST /api/v1/admin/merchants/{merchantId}/disable`，全部挂 `pet.admin.trade.http.enabled` 家族开关（63号 v0.1 既有面开关，默认关，前置 `pet.auth.admin.enabled=true`）；63号 v0.2 为实现契约并在 OpenAPI 以 x-contract=63 登记。worker 侧新增 `pet.admin.trade.worker.enabled`（默认关）与 `pet.admin.trade.migration-enabled/migration-database`（隔离 opt-in 迁移，同 V27 先例）。
2. **动作码部署拼写**：提案 `refund.channel-query`/`order.abnormal-close` 含连字符，部署词法（AdminActionCheckQuery）只允许小写点分段——按 `service.force.offline` 先例做实现期拼写修正为 `refund.channel.query`/`order.abnormal.close`（PR 披露）；`refund.retry`/`merchant.disable` 照提案。
3. **渠道查单/重试**：不发明新任务类型——按退款内核既有按 source 前缀的 durable 任务族（`<前缀>REFUND_CHANNEL_QUERY`/`<前缀>REFUND_SUBMIT`）重推：任务不存在按原排期补建（query 任务保持不可变首查排期），DEAD/CANCELED/SUCCEEDED 经 owner recovery（JdbcAsyncTaskRecoverer）复活，READY/RUNNING/RETRY_WAIT 原样返回现状（在途幂等）。退款 SUCCESS 一律 409；query 对未提交渠道（CREATED）退款 409（retry 管提交）。PAYMENT 持久边界仍是唯一渠道事实裁决者；本动作只重推。
4. **异常关单**：固定语义异常关闭+全额退款。走 order 既有状态机：`order_stage→CANCELED`（不新增订单状态），`cancel_reason='ADMIN_ABNORMAL_CLOSE'` 为既有事实列新值（PR 披露）；原因码定词表 `STORE_OFFLINE|QUALIFICATION_INVALID|MERCHANT_UNREACHABLE|OTHER`（取 §5.5 适用清单），reason 10..500 字。守卫与商家拒单关闭同款：PAID+UNVERIFIED、canceled/cancel_reason/refund_order_id/current_refund_application_id/current_aftersale_id 均空、refunded_amount=0——**在途售后/退款申请一律拒绝（409）而非失效**，"refund_order 创建后禁止核销""未履约售后在 refund_order 创建前仍可核销"不因本动作改变。全额退款经退款内核以既有 source 族增一员 `ADMIN_ABNORMAL_CLOSE`（initiator_type=OPS，金额=渠道实付，任务/对账/问题单机制全复用，任务前缀 `ADMIN_`）；ORDER 为唯一事实源（OrderAdminCloseFactsApi.requireClosed 提供 origin 证明，退款/支付内核按既有 per-source 证明纪律读取）。退款成功投影/预约释放复用既有消费者纪律（新消费者 ORDER_ADMIN_REFUND：claim+投影+释放同事务）。无完成事件 by design（C/M 面读 order 事实；退款链既有事件照常；同服务强制下架"无事件"先例）——PR 披露。
5. **商家下线**：复用既有商家状态词表 ACTIVE→OFFLINE（不新增状态）。新订单经既有准入事实（MerchantOrderEligibilityPolicy 对非 ACTIVE fail-closed）停止；存量订单/退款/售后经既有内核对 OFFLINE 商家的在途准入照常；在途订单不拒绝本动作（§5.6）。reason 10..500 字。无事件 by design（消费方读权威状态事实）——PR 披露。
6. **幂等与审计**：每动作一行 append-only 审计（`refund_admin_action`/`order_admin_action`/`merchant_governance_action`，V32 隔离 opt-in 迁移；操作者/authzVersion/requestId/traceId/时间），request_id 唯一键即幂等绑定：同 ID 同参重放返回原回执，同 ID 异参 409 `IDEMPOTENCY_KEY_CONFLICT`；写请求必带终端 X-Request-Id（23号）。无新增错误码（复用 COMMON_CONFLICT/IDEMPOTENCY_KEY_CONFLICT/ORDER_STATE_NOT_ALLOWED/ORDER_REFUND_ALREADY_CREATED/ORDER_OPERATION_BUSY 等）。
7. **权限与数据范围**：控制器快照校验动作码+ADMIN_WEB 受众+ALL 范围（沿 63号 v0.1 口径，CITY/MERCHANT 一律 403）；内核经注入 AdminAuthorizationQueryApi 每次 EXECUTE 相位重验（resource=REFUND_ORDER/ORDER/MERCHANT）。RBAC 登记四处动作码于 DEPLOYED_ACTIONS（超管默认可见，普通账号按授予）。
8. **读侧联动**：63号 v0.1 退款列表 sourceType 过滤词表增 `ADMIN_ABNORMAL_CLOSE`（读侧词表随内核实际写入词汇，契约原文即如此约定）。

## 不做什么

- 不实现 `merchant.freeze`/`merchant.unfreeze`/`benefit-compensation.*`/导出/代接单/代支付/手改渠道事实（AUTH-001 禁止登记与未授权项）。
- 不改 DisplayOrderStatus 真值表（CANCELED+PAID+退款事实的既有展示分支已覆盖异常关单形态）。
- 不新增订单状态、商家状态、任务类型、错误码；不放宽任何 SSOT 硬规则。
