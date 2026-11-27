# 支付中心订单分账与结算契约 v0.1（CCR-PAY-CENTER-001 阶段 2）

状态：DRAFT（阶段 2 随代码实现交付，开关默认关闭；不授权生产迁移、合并或启用）。
依据：
- [40 号裁决补充](../01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md)（APPROVED）
- [CCR-PAY-CENTER-001](../../planning/ccr/CCR-PAY-CENTER-001.md)（APPROVED，含 2026-11-25 §13 渠道文档核实）
- [75 号支付中心收款契约](./75-Payment-Center-Contract-v0.1.md)（APPROVED，含 §5.5 闪价分账基数口径）
- 拉卡拉本地权威文档 `C:\Users\Administrator\Desktop\拉卡拉文档`：1111、1112、2396、1293、1296、1297、委托书、SDK 1.0.8 参考

Schema：[SQL78](../03-database/78-Payment-Settlement-Schema-v0.1.sql)、[SQL79](../03-database/79-Payment-Settlement-Fallback-Schema-v0.1.sql)；隔离 Flyway 迁移：`backend/pet-boot/src/main/resources/db/settlement-migration/V38__payment_settlement.sql` 与 `V39__payment_settlement_fallback.sql`。

---

## 1. 范围

本阶段交付：
1. **1111/1112 对账日期 `log_date` 补齐**（依据 2026-11-25 原始文档补核）：在不破坏阶段 1 行为的前提下，解决真实 1111/1112 报文无 `log_date` 字段导致 `log_no`/`log_date` 均落 NULL 的问题。
2. **拉卡拉 v3 订单分账协议与 HTTP 客户端**（`LakalaSacsProtocol`、`LakalaSacsHttpClient`）：覆盖 2396 可分账金额查询、1293 订单指令分账、1296 订单分账结果查询（增强版）、1297 分账结果通知验签与解析。
3. **结算配置与审计**（`settlement_config`、`settlement_config_audit`）：按商家配置 T+d 天数（默认 7，范围 7～30）与对外手续费率（默认 `0.6000`%，4 位小数），写操作 `requestId` 幂等与改前/改后审计。
4. **结算单与 T+d 生命周期**（`settlement_order`、`settlement_instruction`、`settlement_instruction_receipt`）：
   - 消费 `PaymentSucceededEvent.v1`（`settle_type='1'`）创建 `PENDING_TRIGGER` 结算单，快照对外费率与订单金额构成；
   - 消费 `OrderVerifiedEvent.v2` 快照 `settle_days`，计算 `eligible_at = verified_at + settle_days`，转 `WAITING` 并同事务登记 `SETTLEMENT_DUE` 延迟任务；
   - 消费 `RefundOrderCreatedEvent.v1` / `RefundSucceededEvent.v1` 处理分账前暂停、部分退款按比例重算与全额退款作废；
   - 提供业务无关 `SettlementTriggerApi`，支持 `DEMO` / 内部业务方闭环验证；
   - `SETTLEMENT_DUE` 到期复核接收方激活状态（阶段 2 默认 fail-closed 端口，阶段 4 接实库）、对账追踪字段、在途退款/售后阻塞，调用 2396 查可分账额 → 计算实分额与平台补贴应付 → 先落库 `SENT` 并同事务登记 `SETTLEMENT_CHANNEL_QUERY`（15s 首档退避）→ 事务外提交 1293；
   - `SETTLEMENT_CHANNEL_QUERY`（退避 15s/1m/5m/15m/1h/6h）与 1297 通知共用行锁 + 回执去重写入器，仅由已验签查询/通知确认终态，同事务发布 `SettlementSplitSucceededEvent.v1` / `SettlementSplitFailedEvent.v1`。
5. **内部 API**：`pet-payment-api` 新增 `SettlementAdminApi`、`SettlementQueryApi`、`SettlementTriggerApi`；`pet-order-api` 新增只读 `OrderSettlementFactsApi`。

本阶段不做：分账回退与退款前置（阶段 3）、分账接收方开通与拉卡拉 v2 协议（阶段 4）、钱包与提现（阶段 5）、补偿扫描与日对账及补贴报表汇总（阶段 6）、公开 HTTP 控制器与前端页面（阶段 7）、ORDER 切入 `PaymentCenterApi`（阶段 8）。

---

## 2. 1111/1112 `log_date` 权威解析规则（补充 75 号契约 §3）

依据 2026-11-25 核实的 1111/1112 原始文档：
- 1111 查单响应含 `log_no`（M，String 14，即 `trade_no` 后 14 位）、`trade_no`（M，String 32，前 8 位为 POSP 日期 `yyyyMMdd`）、`trade_req_date`（M，String 8，`yyyyMMdd`），无 `log_date` 字段；
- 1112 交易通知含 `log_no`（M，String 14）、`trade_no`（M，String 32，前 8 位为 `yyyyMMdd`）、`trade_req_date`（O，String 8），无 `log_date` 字段。

`LakalaProtocol.collectTrace(JsonNode data)` 改为以下优先级（仍为可选字段，缺失或非法一律返回 `(null, null)`，不阻断支付成功，不从 `trade_time` 推导）：
1. 必须存在合法 `log_no`（文本，匹配 `[0-9A-Za-z]{1,32}`）；否则返回 `(null, null)`。
2. 若 JSON 显式包含 `log_date` 键：严格校验其为合法 `yyyyMMdd` 文本（严格日历），不合法直接返回 `(null, null)`（不回退）。
3. 若 JSON 不含 `log_date` 键：
   - 若存在 `trade_req_date` 且非空：严格校验为合法 `yyyyMMdd`；若同时存在 `trade_no` 为纯数字且满足 `yyyyMMdd` 前缀 + `log_no` 后缀（兼容文档 22 位与生产 32 位）且日期与 `trade_req_date` 冲突，返回 `(null, null)`；
   - 否则仅当 `trade_no` 为长度 `8 + log_no.length()`～`32` 的纯数字、前 8 位为合法 `yyyyMMdd` 且后缀等于 `log_no` 时，取前 8 位作为 `log_date`；
   - 其余情况返回 `(null, null)`。

---

## 3. 金额与分账口径

沿用 40 号 §2/§3、CCR §5、75 号 §5.5：

1. **原始分账基数**：
   `base0 = original_price - flash_discount_amount - merchant_discount_amount`（均 `>= 0.00`；`merchant_discount_amount` 取订单快照 `pet_order.merchant_coupon_discount`，见[契约 79](./79-Merchant-Coupon-Campaign-Contract-v0.1.md)，无商家券为 `0.00`）。
2. **分账前退款重算**：
   - `refunded_amount >= paid_amount` → 结算单 `CANCELED`（`split_base = 0.00`, `merchant_share = 0.00`）。
   - `0 < refunded_amount < paid_amount` → 按已付剩余比例向下取整到分：
     `split_base = floor_cents(base0 * (paid_amount - refunded_amount) / paid_amount)`。
   - `refunded_amount == 0` → `split_base = base0`。
3. **商家份额**：
   `fee_rate` 按百分比 4 位小数保存（默认 `0.6000`，即 `0.006`）：
   `merchant_share = floor_cents(split_base * (1 - fee_rate / 100))`。
4. **实分金额、平台留存与平台补贴应付**（执行分账调用 2396 后按渠道返回 `can_separate_amt` 精确计算）：
   - `min_platform_amount = ceil_cents(paid_amount * lowest_ratio)`（`lowest_ratio` 取配置 `pet.payment.settlement.lowest-ratio`，范围 `[0, 1)`，默认 `0`）。
   - `max_merchant_split = max(0.00, can_separate_amt - min_platform_amount)`。
   - `split_amount = min(merchant_share, max_merchant_split)`。
   - `platform_subsidy_due = merchant_share - split_amount`（`>= 0.00`）。
   - `platform_retained_amount = can_separate_amt - split_amount`（`>= 0.00`）。
   - 若 `can_separate_amt <= 0.00`：不得提交 1293，结算单置 `ON_HOLD`（`hold_reason = ZERO_SEPARABLE_AMOUNT`）等待人工核查。
5. **1293 提交报文**（固定金额模式 `cal_type = "0"`）：
   - `total_amt = cents(can_separate_amt)`（`= cents(split_amount) + cents(platform_retained_amount)`）。
   - `recv_datas`：
     - `platform_retained_amount > 0` 时加入 `{"recv_merchant_no": platformMerchantNo, "separate_value": cents(platform_retained_amount)}`；
     - `split_amount > 0` 时加入 `{"recv_no": merchantReceiverNo, "separate_value": cents(split_amount)}`；
     - 至少包含一条明细，且各 `separate_value` 之和严格等于 `total_amt`。
   - `lkl_org_no`：配置非空时上送，为空时省略；`notify_url`：配置非空时上送，为空时省略。

---

## 4. 拉卡拉 v3 SACS 渠道协议（已按 PDF + SDK 1.0.8 核实）

统一信封：`{"req_time":"yyyyMMddHHmmss","version":"3.0","req_data":{...}}`（无 `out_org_code`）；请求加签与响应验签沿用 1004 五行 `SHA256withRSA`；通知验签沿用 1009 三行 `Authorization` 头。

| 接口 | 路径 | 成功码 | 关键字段与校验 |
|---|---|---|---|
| 2396 可分账金额查询 | `/api/v3/sacs/queryAmt` | `SACS0000` | 请求 `merchant_no`/`log_no`/`log_date`；响应校验回传三元组一致，解析 `total_separate_amt`/`can_separate_amt`（分，非负整数字符串） |
| 1293 订单指令分账 | `/api/v3/sacs/separate` | `SACS0000` | 请求 `merchant_no`/`log_no`/`log_date`/`out_separate_no`/`total_amt`/`cal_type="0"`/`recv_datas` 及可选 `lkl_org_no`/`notify_url`/`remark`；响应校验 `out_separate_no` 一致，提取 `separate_no` 与 `status`；非 `SACS0000` 或网络/验签异常一律视为结果未知（保持 `SENT`，仅用原 `out_separate_no` 查单，绝不自动换号重发） |
| 1296 分账结果查询（新） | `/api/v3/sacs/query_plus` | `SACS0000` | 请求 `merchant_no` + `separate_no`（优先）或 `out_separate_no`；响应兼容 `resp_data` 与失败示例中的 `respData`；校验 `out_separate_no`、`cmd_type`、`log_no`、`log_date`；以 `final_status`（缺失时回退 `status`）判定终态（`SUCCESS`/`FAIL`）与中间态（`ACCEPTED`/`PROCESSING`）；解析 `actual_separate_amt`/`total_fee_amt`/`acc_result_desc`/`finish_date` 及 `detail_datas`（`amt`/`actual_amt`/`fee_amt` 兼容字符串与数字） |
| 1297 分账结果通知 | `notify_url`（由外部回调路由入 `SettlementCallbackService`） | 商户应答 `{"code":"SUCCESS","message":"执行成功"}` | 先按 1009 验签原始字节，校验 `out_separate_no`/`separate_no`/`cmd_type`；阶段 2 处理 `cmd_type=SEPARATE`，对 `FALLBACK`/`CANCEL` 失败关闭待阶段 3 接管；状态以 `final_status`（缺失时回退 `status`）为准 |

---

## 5. 状态机与并发纪律

### 5.1 `settlement_order.status`
- `PENDING_TRIGGER` → `WAITING`（核销完成，快照 `settle_days`、`eligible_at`，同事务登记 `SETTLEMENT_DUE`）
- `PENDING_TRIGGER` / `WAITING` / `ON_HOLD` → `CANCELED`（全额退款成功）
- `WAITING` → `ON_HOLD`（在途退款/售后、接收方非 ACTIVE、缺 `log_no`/`log_date`、`can_separate_amt <= 0`）
- `ON_HOLD` → `WAITING`（阻塞解除或运营恢复，重新登记 `SETTLEMENT_DUE`）
- `WAITING` → `SPLITTING`（锁结算单，落 `settlement_instruction(SENT)` 并同事务登记 `SETTLEMENT_CHANNEL_QUERY`，`active_instruction_id = instructionId`）
- `SPLITTING` → `SPLIT_SUCCEEDED`（1296/1297 确认终态 `SUCCESS`，同事务发布 `SettlementSplitSucceededEvent.v1`）
- `SPLITTING` → `SPLIT_FAILED`（1296/1297 确认终态 `FAIL`，同事务发布 `SettlementSplitFailedEvent.v1`）
- `SPLIT_FAILED` → `WAITING`（运营显式 `retryFailedOrder`，`requestId` 幂等，要求当前无 `SENT`/`PROCESSING`/`SUCCEEDED` 指令）

### 5.2 `settlement_instruction.status`
`SENT → PROCESSING → SUCCEEDED / FAILED`（终态仅由已验签 1296 查询或 1297 通知确认；发往 1293 前即落 `SENT` 并同事务登记查询任务）。

### 5.3 并发与幂等
- `settlement_order` 唯一键：`uk_settlement_order_payment(payment_id)`、`uk_settlement_order_biz(biz_type, biz_no)`。
- `settlement_instruction` 唯一键：`uk_settlement_instr_out_no(out_separate_no)`、`uk_settlement_instr_attempt(settlement_order_id, instruction_type, attempt_no)`、`uk_settlement_instr_separate_no(separate_no)`。
- `settlement_instruction_receipt` 唯一键：`uk_settlement_receipt_dedupe(instruction_id, receipt_sha256)`。
- 所有状态推进均先 `SELECT ... FOR UPDATE` 锁定 `settlement_order`（以及关联 `settlement_instruction`）并带 `version` 条件更新。
- 1297 通知 × 1296 查询并发竞争共用 `SettlementReceiptWriter`，由行锁 + 回执摘要去重 + `result_event_id` 唯一约束保证至多发布一次终态事件。
- 写操作（配置修改、人工恢复、失败重试、DEMO 触发）均强制 `requestId` 幂等绑定，同键异参返回 `IDEMPOTENCY_KEY_CONFLICT`。

---

## 6. 任务与事件

### 6.1 AsyncTask（ownerModule=`PAYMENT`）
- `SETTLEMENT_DUE`：`taskKey = SETTLEMENT_DUE:{settlementOrderId}`（重排/重试可带轮次或使用唯一键 `SETTLEMENT_DUE:{settlementOrderId}:{round}` 避免已完成任务键碰撞），`availableAt = eligible_at`，临时渠道异常退避 `1m / 5m / 15m / 1h`。
- `SETTLEMENT_CHANNEL_QUERY`：`taskKey = SETTLEMENT_CHANNEL_QUERY:{instructionId}`，首档延迟 `15s`，退避序列 **`15s / 1m / 5m / 15m / 1h / 6h`**，`maxRetryCount = 20`，超限置 DEAD（`SETTLEMENT_QUERY_EXHAUSTED`）供运营/阶段 6 补偿处理。

### 6.2 Outbox 事件（详见 08 号事件目录）
- 生产：`SettlementSplitSucceededEvent.v1`、`SettlementSplitFailedEvent.v1`（`aggregateType=SETTLEMENT`, `aggregateId=settlementOrderId`）。
- 消费：`PaymentSucceededEvent.v1`（`payment-settlement-on-paid`）、`OrderVerifiedEvent.v2`（`payment-settlement-on-verified`）、`RefundOrderCreatedEvent.v1`（`payment-settlement-on-refund-created`）、`RefundSucceededEvent.v1`（`payment-settlement-on-refund-succeeded`）。

---

## 7. 配置与开关（遵循 PR #234 五层配置规范）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `pet.payment.settlement.enabled` | `false` | 阶段 2 总开关；开启时强依赖 `pet.payment.enabled=true` + 平台商户 + 拉卡拉凭证，否则启动失败关闭 |
| `pet.payment.settlement.lowest-ratio` | `${LAKALA_SPLIT_LOWEST_RATIO:0}` | 委托书"本商户最低需分得"比例（小数 `[0, 1)`，默认 `0`）；值域外启动失败 |
| `pet.payment.settlement.org-code` | `${LAKALA_ORG_CODE:}` | 可选 `lkl_org_no`，非空时随 1293 上送 |
| `pet.payment.settlement.notify-url` | `${LAKALA_SEPARATE_NOTIFY_URL:}` | 可选 1293 `notify_url`，非空时上送 |
| `pet.payment.settlement.migration-enabled` | `false` | 隔离库 Flyway 迁移开关，仅允许 `paysettle_*` 库名 |
| `pet.payment.settlement.migration-database` | `""` | 与当前 DataSource catalog 精确一致时方可执行 `db/settlement-migration` |

---

## 8. 阶段 3 补充：分账回退（2395）与退款前置拦截

依据 40 号 §6 与 CCR-PAY-CENTER-001 §8 / §12 / §13.1（`拉卡拉开放平台-2395.pdf`、`1296.pdf`、`1297.pdf`）：

### 8.1 拉卡拉 2395 分账回退协议（`/api/v3/sacs/fallback`）
- 沿用 v3 SACS 信封（`req_time`/`version=3.0`/`req_data`）与 1004 五行加签验签；V1 不使用 2394 撤销，全额与部分退款统一走 2395 回退。
- 请求字段：`merchant_no`（M）、`origin_separate_no` / `origin_out_separate_no`（C，二选一，优先 `origin_separate_no`）、`out_separate_no`（M，如 `FB{instructionId}`）、`fallback_reason`（C，≤128）、`total_amt`（M，分，正整数字符串）、`origin_recv_datas`（M，严格 1 个元素：`[{"recv_no": "<商家接收方编号>", "amt": "<分>"}]`，`amt == total_amt`）。
- 响应校验：`code=SACS0000`、`out_separate_no` 与 `total_amt` 透返一致、提取 `separate_no` 与 `status`；非 `SACS0000` 或网络/验签异常视为结果未知（保持 `SENT`，仅由 `SETTLEMENT_CHANNEL_QUERY` 15s/1m/5m/15m/1h/6h 查单或 1297 通知确认，绝不自动换号重发）。
- 1296 查单与 1297 通知均按 `cmd_type=FALLBACK` 路由至 `SettlementReceiptWriter` 确认终态（`final_status` 优先，缺失回退 `status`）；账户端余额不足失败返回 `code=SACS0000` + `final_status=FAIL` + `acc_result_desc`。

### 8.2 回退金额与 180 天窗口
- `remaining_reversible = max(0.00, origin_split.merchant_amount - settlement_order.reversed_amount)`。
- 若 `remaining_reversible == 0.00`：无需回退，直接放行原路退款。
- 若为全额或末笔累计全额退款（`prior_refunded + refund_amount >= paid_amount`）：`fallback_amount = remaining_reversible`（消除多次部分退款的向下取整尾差）。
- 若为部分退款：`fallback_amount = min(remaining_reversible, floor_cents(refund_amount * (1 - fee_rate_snapshot / 100)))`；若结果为 `0.00` 则直接放行原路退款。
- 窗口校验：以原成功分账指令完成时间起算 **180 天**内允许回退；超期则 `settlement_order` 置 `REVERSE_FAILED`（`FALLBACK_WINDOW_EXPIRED`），暂停退款并告警，平台不垫付。
- 分账后退款成功（消费 `RefundSucceededEvent.v1`）同步刷新 `refunded_amount`、`split_base`、`merchant_share` 与 `platform_subsidy_due = max(0.00, merchant_share - max(0.00, split_amount - reversed_amount))`。

### 8.3 同步退款前置门禁（`PaymentRefundService` ↔ `SettlementRefundGate`）
- 门禁在 `pet-payment-biz` 内部同步执行，不依赖事件，且在完成既有退款身份与业务事实校验之后、创建 `payment_refund_dispatch` / 调用渠道退款之前生效：
  1. 无结算单或 `PENDING_TRIGGER` / `ON_HOLD` / `SPLIT_FAILED` / `CANCELED` → 放行原路退款；
  2. `WAITING` → 在 `settlement_order FOR UPDATE` 锁内同步置 `ON_HOLD(REFUND_IN_PROGRESS)` 阻断并发到期分账，随后放行原路退款；
  3. `SPLITTING` 或同结算单其他退款正在 `REVERSING` → 返回 `QUERY_PENDING`（+15s），待状态收敛后由退款查单任务自动续推；
  4. 本 `refund_order_id` 已有 `SUCCEEDED` 回退指令 → 放行原路退款；已有 `SENT`/`PROCESSING` 回退指令 → 返回 `QUERY_PENDING`（+15s）；最新回退指令为 `FAILED` 或结算单处于 `REVERSE_FAILED` → 返回 `RECONCILIATION_REQUIRED`（暂停退款、写问题单并告警，绝不执行原路退款、平台不垫付）；
  5. `SPLIT_SUCCEEDED` / `PARTIALLY_REVERSED` 且本退款尚无回退指令 → 锁内落 `settlement_instruction(FALLBACK, SENT, refund_order_id, origin_instruction_id)`、结算单转 `REVERSING`、同事务登记 `SETTLEMENT_CHANNEL_QUERY`（+15s）→ 事务外提交 2395 → 返回 `QUERY_PENDING`（+15s）。
- 后续 `REFUND_CHANNEL_QUERY` 触发 `PaymentRefundService.queryRefund` 时，若尚未下发渠道退款（无 `payment_refund_dispatch`）且门禁返回放行（回退已 `SUCCEEDED`），则自动衔接执行原路退款首提（`MAY_HAVE_SENT` 纪律不变）。

### 8.4 运营重试失败回退（`SettlementAdminApi.retryFailedFallback`）
- `settlement_order.status` 新增 `REVERSE_FAILED`；终态成功回退按累计额推进为 `PARTIALLY_REVERSED` 或 `REVERSED`。
- 商家补足余额后，运营可凭 `requestId` 幂等调用 `retryFailedFallback(settlementOrderId, refundOrderId, reason)`：校验结算单 `REVERSE_FAILED`、无在途或已成功回退、未超 180 天窗口后，以 `attempt_no + 1` 创建新回退指令并重新提交 2395。

