# CCR-PAY-CENTER-001 支付中心：订单分账、T+d 与商家提现

- 状态：**APPROVED**（2026-11-04 用户批准）。
- 提出方：Kiro（2026-11-04 与用户逐项讨论后起草）
- 产品依据：[40 号裁决补充](../../docs/01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md)（APPROVED）
- 当前 Contract：40/41/42 号支付与迟到退款契约、49 号退款申请、23 号幂等、09 号调度、12 号错误码
- 是否涉及产品裁决：**YES**（覆盖 32 号 §一，见 40 号 §0）

## 1. 发现的问题

1. 32 号裁决 V1 不做分账/提现；用户 2026-11-04 改为纳入。
2. 现有 `pet-payment` 预下单未传 `settle_type`，交易不会进入待分账。
3. 订单分账请求必须带拉卡拉对账流水号 `log_no` 与交易日期 `log_date`；现有支付通知与查单不保存这两个值。
4. 现有商户绑定按门店配置商户号（`pet.payment.lakala.stores.{storeId}`），与"平台唯一收单商户"模型不符。
5. 现有支付意图与 ORDER 强耦合（`OrderPaymentFactsApi`、门店 guard），不能被其他业务直接复用。
6. 没有结算、分账、接收方、提现任何能力。

## 2. 范围

### 2.1 本 CCR 做

1. 支付中心对外契约（bizType + bizNo 解耦）及 ORDER 接入适配。
2. 收款补齐：分账标识、`log_no`/`log_date`、平台单商户号配置。
3. 结算单、T+d 到期、订单分账、分账查询、分账回退。
4. 分账接收方开通（运营代填、拉卡拉接口提交、审核回调与查询）。
5. 商家钱包：余额查询、手动提现、提现查询与通知。
6. 结果确认：回调 + AsyncTask 主动查询 + 定时补偿扫描 + 日对账。
7. 运营网页与小程序商家端页面（见 §9）。
8. 环境变量模板 `ops/payment-center.env.example`。

### 2.2 本 CCR 不做

余额分账、定向转账、平台自建资金冻结、保证金、短信验证码、商家自助填写收款资料、
系统内标记线下打款到账、MQ 延迟队列（列为后期，见 §6.2）。

## 3. 模块与依赖

方案：扩展现有 `pet-payment-api/biz` 作为支付中心（复用已验证的签名、派发、通知、查单关单、退款），不新建模块。

```
pet-payment-api
  command/PaymentCenterApi        create / query（bizType, bizNo）
  command/RefundCenterApi         既有 PaymentRefundApi 的业务无关入口
  command/SettlementAdminApi      商家结算配置（d、对外费率、启停）
  command/ReceiverAccountApi      接收方开通 / 变更 / 提交 / 查询
  command/WalletCommandApi        提现
  query/SettlementQueryApi        结算单、分账指令、平台补贴报表
  query/WalletQueryApi            余额、提现记录、明细
  dto/ enums/ error/

pet-payment-biz
  collect/      收款（现有，补字段）
  refund/       退款（现有，加分账回退前置）
  settlement/   结算单、T+d、分账、回退
  receiver/     接收方开通
  wallet/       余额、提现
  reconcile/    补偿扫描、日对账
  channel/lakala/  所有拉卡拉接口；复用现有 LakalaHttpClient 签名/验签
```

依赖规则：
- `pet-payment-biz` 只依赖其他模块的 `*-api`，不依赖任何 `*-biz`。
- ORDER → `PaymentCenterApi`（发起支付、退款）；核销完成通过既有 Outbox 事件 `OrderVerifiedEvent.v2` 由支付中心消费，启动 T+d。
- 支付中心 → 事件：`PaymentSucceededEvent.v1`（现有）、`SettlementSplitSucceededEvent.v1`/`SettlementSplitFailedEvent.v1`、`WithdrawalSucceededEvent.v1`/`WithdrawalFailedEvent.v1`。
- 拉卡拉 SDK 1.0.8 **不引入**，只作字段参考（理由：非中央仓库、全局静态配置、旧版 bcprov/httpclient/javax.servlet 与 Boot 3 冲突风险）。

闭环优先：先用测试专用 `bizType=DEMO` 跑通"支付→确认→结算单→模拟核销→T+d 分账→余额→提现"，再把订单支付切到 `bizType=BOOKING_ORDER`。

## 4. 状态机

### 4.1 支付单（沿用现有，新增字段）

沿用 `payment_order` 现有状态。新增 `settle_type`、`log_no`、`log_date`、`biz_type`、`biz_no`（历史行 NULL，不回填猜测）。

### 4.2 结算单 `settlement_order`

| 当前 | 触发 | 下一状态 |
|---|---|---|
| — | 支付成功（快照对外费率） | PENDING_TRIGGER |
| PENDING_TRIGGER | 核销完成（快照 d，eligibleAt = T + d） | WAITING |
| PENDING_TRIGGER / WAITING | 全额退款成功 | CANCELED |
| WAITING | 进行中的退款/售后、接收方非 ACTIVE | ON_HOLD |
| ON_HOLD | 阻塞解除（按已退款重算） | WAITING |
| WAITING | 到期且接收方 ACTIVE，分账指令已持久化 | SPLITTING |
| SPLITTING | 指令终态成功 | SPLIT_SUCCEEDED |
| SPLITTING | 指令终态失败 | SPLIT_FAILED（重试或人工） |
| SPLIT_SUCCEEDED | 分账后退款 | REVERSING |
| REVERSING | 回退成功 | PARTIALLY_REVERSED / REVERSED |
| REVERSING | 回退失败 | REVERSE_FAILED（退款暂停，人工） |

### 4.3 渠道指令 `settlement_instruction`（分账、回退共用）

`CREATED → SENT（结果未知）→ PROCESSING → SUCCEEDED / FAILED`。终态只由查询或已验签通知确认。

### 4.4 接收方 `settlement_receiver`

`DRAFT → RECEIVER_APPLYING → RECEIVER_CREATED → BIND_APPLYING → ACTIVE`；任一审核不通过 `→ REJECTED`（修改后可重提）；
`ACTIVE → CHANGE_APPLYING → ACTIVE`；`ACTIVE → DISABLED`。

### 4.5 提现 `withdrawal_order`

`CREATED → SUBMITTED（结果未知）→ PROCESSING → SUCCEEDED / FAILED`。

## 5. 金额

- 内部 `BigDecimal` / `DECIMAL(18,2)`；渠道按分提交。
- 分账基数 = 订单原价 − 商家出资优惠（V1 为 0）。
- 商家份额 = floor(分账基数 × (1 − 对外费率)) 到分。
- 实分金额 = min(商家份额, 本笔可分账金额 − ceil(平台最低分得比例 × 交易金额))；最低比例取 `LAKALA_SPLIT_LOWEST_RATIO`；不足部分记 `platform_subsidy_due`，进入平台补贴应付报表。
- 部分退款：分账前按比例重算；分账后回退金额 = 退款额 × (1 − 对外费率)，按分向下取整，合计不超过已分金额。
- 提交使用固定金额模式 `calType=0`。

## 6. 结果确认与可靠性

### 6.1 V1

| 层 | 机制 |
|---|---|
| 回调 | 支付 1112、退款 1904、分账 1297、提现 1272、接收方/绑定 retUrl。先验签，再 CAS 更新，最后应答 |
| 主动查询 | 每次渠道请求与查询任务**同事务**登记到现有 durable AsyncTask（`next_run_at` 即数据库延迟队列）。退避：支付 5s/15s/30s/1m/2m/5m 至支付截止；分账/回退 15s/1m/5m/15m/1h/6h，提现 1m/5m/15m/1h/6h，上限后转人工 |
| 到期触发 | 核销时登记 `SETTLEMENT_DUE` 任务（run_at = eligibleAt） |
| 补偿扫描 | 每 5 分钟扫描非终态且超时、或任务已 DEAD/丢失的记录，重新登记或告警；兜底到期触发 |
| 日对账 | 拉取对账文件与分账结果列表（1879），差异写 `reconcile_diff` 并告警，不自动改账 |

发送纪律：先落库（生成渠道流水号、状态 SENT）→ 事务外调用渠道 → 未知结果只用原号查询，不换号重发，超时不判失败。

### 6.2 后期：MQ 延迟队列

Outbox 投递 MQ，延迟消息唤醒查询任务。数据库状态仍为准，处理器幂等不变（09 号调度文档第 79 行已预留）。不在本 CCR 实施。

### 6.3 并发

- 状态推进一律带版本号条件更新，SQL 写在模块 Mapper XML。
- 退款与分账竞争：两者都先 `SELECT … FOR UPDATE` 锁结算单；退款方先置 ON_HOLD，分账方发送前复核。
- 唯一约束：`out_separate_no`、提现 `mer_order_no`、渠道流水号、`settlement_order.payment_id`。
- 写操作 requestId 幂等（运营配置、接收方提交、商家提现）。

## 7. Schema 影响（新迁移目录 `settlement-migration`；版本号在实施时按各迁移目录当时最大值顺延，不预占）

| 表 | 主要字段 |
|---|---|
| `payment_order`（改） | + `biz_type`、`biz_no`、`settle_type`、`log_no`、`log_date` |
| `settlement_config` | merchant_id、settle_days(7~30)、fee_rate DECIMAL(7,4)、enabled、version、审计时间 |
| `settlement_config_audit` | 改前值、改后值、操作人、requestId |
| `settlement_receiver` | merchant_id、receiver_no、status、加密账户资料、附件引用、渠道申请单号、审核结果、version |
| `settlement_order` | payment_id、merchant_id、split_base、fee_rate_snapshot、merchant_share、split_amount、platform_subsidy_due、merchant_discount_amount、platform_subsidy_amount、verified_at、settle_days_snapshot、eligible_at、reversed_amount、status、version |
| `settlement_instruction` | settlement_order_id、type(SPLIT/FALLBACK)、out_separate_no、separate_no、amount、status、回执摘要 SHA-256 |
| `withdrawal_order` | merchant_id、receiver_no、amount、mer_order_no、draw_jnl、status、request_id |
| `reconcile_diff` | 来源、业务键、差异类型、状态 |

敏感字段（卡号、证件号、手机号）AES-GCM 加密，密钥来自部署配置。

## 8. API / Event 影响

内部 API 见 §3。HTTP（路径以契约定稿为准）：
- 运营：`/api/v1/admin/settlement/configs`、`/receivers`、`/orders`、`/instructions`、`/withdrawals`、`/subsidy-report`、异常重试。
- 商家：`/api/v1/m/wallet`（余额/待结算）、`/m/wallet/withdrawals`（提现、列表）、`/m/settlement/receiver`（只读）。
- 回调：`/callbacks/lakala/separate`、`/withdrawals`、`/ledger`（接收方/绑定审核）。

新增 RBAC 动作码：`settlement.config.write`、`settlement.receiver.write`、`settlement.receiver.read-sensitive`、
`settlement.instruction.retry`、`settlement.report.read`；商家侧提现仅 OWNER。

新增事件：`SettlementSplitSucceededEvent.v1`、`SettlementSplitFailedEvent.v1`、`WithdrawalSucceededEvent.v1`、`WithdrawalFailedEvent.v1`。
消费既有事件：`OrderVerifiedEvent.v2`（起算 T）、`RefundOrderCreatedEvent.v1`（结算单置 ON_HOLD）、`RefundSucceededEvent.v1`（重算或作废）。
分账回退前置不靠事件：退款执行在支付模块内部，提交渠道退款前同步检查结算单，已分账则先回退。

## 9. 前端影响

- 运营网页：结算配置、接收方录入与台账、结算/分账/提现台账、平台补贴应付报表、异常处理。
- 小程序商家端：结算账户（只读）、钱包、提现、提现记录。

## 10. 部署开关（全部默认 false）

`pet.payment.center.enabled`、`pet.payment.settlement.enabled`、`pet.payment.settlement.worker.enabled`、
`pet.payment.receiver.enabled`、`pet.payment.wallet.enabled`、对应 `*.http.enabled`。缺凭证时启动失败关闭。
登记到 21 号部署开关清单。

## 11. 测试影响

- 渠道：签名/验签、`settle_type`、`log_no` 解析、未知结果只查不重发、重复通知去重。
- 结算：T+d 边界、d/费率快照、平台券差额、部分退款重算、ON_HOLD 往返。
- 并发：退款 × 分账、通知 × 查询、到期任务 × 补偿扫描只产生一次分账。
- 回退：分账后退款先回退、回退失败退款暂停。
- 提现：仅 OWNER、余额以渠道为准、重复提交幂等。
- 架构：`check-persistence-style.py`、ArchUnit 模块依赖。

## 12. 交付阶段

1. 收款补齐 + 支付中心契约 + DEMO 业务方闭环（SIT 测试参数联调）
2. 结算单 + T+d 到期 + 订单分账 + 查询/通知
3. 分账回退 + 退款前置
4. 接收方开通（运营代填）
5. 钱包与提现
6. 日对账 + 平台补贴报表
7. 运营网页、小程序商家端页面
8. ORDER 接入（`bizType=BOOKING_ORDER`）

每阶段独立 PR，开关默认关闭；不授权合并、生产迁移或启用。

## 13. 渠道文档核实结果（2026-11-04，本地资料 `planning/local/lakala/`）

### 13.1 已核实

| 接口 | 结论 | 对设计的影响 |
|---|---|---|
| 1293 订单分账 `/api/v3/sacs/separate` | 异步；同步响应 `code=SACS0000` 且 `status=PROCESSING/ACCEPTED`；文档建议发出后 ≥15 秒再查询 | 分账查询退避首档改为 15s（15s/1m/5m/15m/1h/6h） |
| 2396 可分账金额 `/api/v3/sacs/queryAmt` | 返回 `total_separate_amt`、`can_separate_amt`（分） | 分账前先查，实分金额以它为上限 |
| 1297 分账结果通知 | `cmd_type` = SEPARATE/CANCEL/FALLBACK；`status`/`final_status`；应答必须返回 `code=SUCCESS`，否则重复通知 | 一个回调入口处理分账、撤销、回退三类 |
| 2395 分账回退 `/api/v3/sacs/fallback` | 异步；`origin_recv_datas` 每次只能一个接收方；180 天内 | 一次回退对应一个商家，符合本设计 |
| 2394 分账撤销 `/api/v3/sacs/cancel` | 金额必须等于原分账金额；30 天内 | V1 不使用撤销，部分/全额退款统一走回退 |
| 1287 接收方创建 | **同步**返回 `receiverNo`；`settleType` 可传 01 主动提款（默认 04）；对私必传身份证正反面、银行卡附件，对公另需营业执照、法人证件 | 状态机：RECEIVER_APPLYING 同步得到结果；创建时传 `settleType=01` |
| 1290 关系绑定 | 异步审核，`retUrl` 回调 `auditStatus` 1 通过 / 2 拒绝，返回 `applyId` | BIND_APPLYING 需回调 + 主动查询（1872） |
| 1277 提款模式设置 | 接收方不支持用该接口变更，需走接收方变更接口 | 删除"调用 1277 设手动"，改为 1287 创建时设定 |
| 1269 余额查询 | v2 报文；金额单位**元**；`curBalance` 为可用余额；`payType=04` 为接收方账户 | 钱包余额以 `curBalance` 为准 |
| 1319 D1 提现 | `drawAmt` 单位**元**；`payType=04`；返回 `drawJnl` | 必传 `merOrderNo` 便于幂等和查询 |
| 1271/1272 提现查询与通知 | `drawState` = DRAW.ACCEPTED/FREEZE/PROCESSING/SUCCESS/FAILED；`drawAmt` 含手续费，`drawFee` 单列 | 提现状态映射：ACCEPTED/FREEZE/PROCESSING→PROCESSING |
| 1484 提款手续费设置 | 可按接收方设置固定或比例手续费，D0/D1 | 提现手续费由拉卡拉按配置扣，平台记录 `drawFee` |
| 委托书 | 勾选"按订单清分""指令清分"；含"本商户最低需分得【 】%" | 新增配置 `LAKALA_SPLIT_LOWEST_RATIO`，影响实分上限 |

| 1296 分账结果查询 `/api/v3/sacs/query_plus` | 按 `out_separate_no` 查；`status`/`final_status`；账户处理失败也返回 `SACS0000`，以 `status=FAIL` 和 `acc_result_desc` 表示；可能收取分账手续费：`actual_separate_amt`、`total_fee_amt`、明细 `actual_amt`/`fee_amt` | 终态以 `final_status` 判定；商家实收记 `actual_amt`，手续费单列，对账核对 |
| 1084 附件上传 `/api/v2/mms/openApi/uploadFile` | Base64，≤5MB，jpg/png/pdf；返回 `attFileId`；**上传后须在 24 小时内用同一 `orderNo` 提交**，否则失效；成功为 `retCode=000000` | 不长期保存 `attFileId`：运营点"提交"时才上传附件并立即调用 1287/1290；原件保存在私有素材存储 |
| 1288 接收方变更 | 同步返回"变更成功"；可改 `settleType` | 状态机 CHANGE_APPLYING 同步得到结果 |
| 1289 接收方详情查询 | 返回接收方名称、证件、账户等 | 用于核对和补偿 |
| 1872 绑定申请列表查询 | 按 `applyId` 查，`auditStatus` 0 待审核 / 1 通过 / 2 拒绝 | BIND_APPLYING 主动查询的依据 |

两套报文格式：
- v3（sacs）：`req_data`/`resp_data`、`code`、成功码 `SACS0000`，与现有 labs 客户端同族；
- v2（mms 接收方/绑定、laep 余额/提现）：`reqData`/`respData`、`retCode`、成功码 `000000`、`timestamp`/`ver`/`reqId`。
渠道适配层需新增 v2 报文封装，签名规则沿用 1004。

### 13.2 仍待核实 / 待定

| 项 | 处理 |
|---|---|
| 分账手续费（1296 `total_fee_amt`）由谁承担、费率多少 | 向拉卡拉确认；产品默认由商家承担（40 号 §2），商家实收以 `actual_amt` 为准 |
| 平台商户尚未开通分账（1284） | 阶段 1 不受影响；阶段 2 起的 SIT 分账联调需开通后进行 |
| 委托书"最低需分得"比例 | 建议填 0%；若拉卡拉不接受 0，平台券差额会相应增大 |
| 回调验签 | 按 1009 统一通知规则，联调时核实分账/提现/绑定回调是否同规则 |
| 渠道时间时区（`LAKALA_CHANNEL_TIMEZONE`） | 向拉卡拉确认 |
| 商家钱包是否展示平台补贴待结算 | 默认不展示 |
| 分账合作协议模板 | 向拉卡拉客户经理索取 |
