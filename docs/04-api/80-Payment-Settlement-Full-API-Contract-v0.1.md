# 支付中心全阶段接口与分账/接收方/提现/对账契约 v0.1（CCR-PAY-CENTER-001 阶段 4～7）

状态：DRAFT（供阶段 4～7 多 Agent 并行开发冻结接口口径，所有开关默认关闭；不授权生产迁移、合并或启用）。
依据：
- [40 号裁决补充](../01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md)（APPROVED）
- [22 号运营权限人工裁决补充](../01-prd/22-运营权限人工裁决补充-v1.0.md)（APPROVED：单运营无双人复核、禁止手工标记资金到账/代商家提现）
- [24 号取消 MFA 裁决补充](../01-prd/24-取消MFA人工裁决补充-v1.0.md)（APPROVED：无额外 MFA）
- [CCR-PAY-CENTER-001](../../planning/ccr/CCR-PAY-CENTER-001.md)（APPROVED）
- [78 号订单分账与回退契约](./78-Payment-Settlement-Contract-v0.1.md)（阶段 2～3 已实现）
- 本地拉卡拉权威文档目录 `C:\Users\Administrator\Desktop\拉卡拉文档`

---

## 1. Flyway 隔离迁移编号分段（严禁冲突）

统一位于 `backend/pet-boot/src/main/resources/db/settlement-migration/`，与 `docs/03-database/` 一字不差：

| 版本 | 阶段 | Schema 文件 | 表清单 |
|---|---|---|---|
| `V38` | 阶段 2（已完成） | `78-Payment-Settlement-Schema-v0.1.sql` | `settlement_config`, `settlement_config_audit`, `settlement_order`, `settlement_instruction`, `settlement_instruction_receipt`, `settlement_action_request` |
| `V39` | 阶段 3（已完成） | `79-Payment-Settlement-Fallback-Schema-v0.1.sql` | `settlement_instruction` 追加 `refund_order_id` / `origin_instruction_id` / `fallback_reason` |
| `V40` | 阶段 4（接收方） | `80-Payment-Receiver-Schema-v0.1.sql` | `settlement_receiver_account`, `settlement_receiver_audit` |
| `V41` | 阶段 5（钱包提现） | `81-Payment-Wallet-Schema-v0.1.sql` | `withdrawal_order`, `withdrawal_receipt` |
| `V42` | 阶段 6（对账差异） | `82-Payment-Reconcile-Schema-v0.1.sql` | `reconcile_diff`, `reconcile_job_run` |

---

## 2. RBAC 动作码与角色边界（CCR §8 / 22 号 / 40 号）

### 运营端动作码（5 个）
- `settlement.config.write`：配置商家 T+d（7～30）与对外手续费率（百分比 4 位小数）
- `settlement.receiver.write`：运营代填/创建/变更/绑定/同步分账接收方
- `settlement.receiver.read-sensitive`：按原因查看分账接收方明文敏感字段（写不可变审计）
- `settlement.instruction.retry`：人工恢复 ON_HOLD 结算单、重试失败分账、重试失败分账回退、处理对账差异
- `settlement.report.read`：只读查询结算配置、接收方脱敏台账、结算单、指令、提现记录、平台补贴应付报表、对账差异

### 商家端权限
- `GET /api/v1/merchant/wallet`、`GET /api/v1/merchant/wallet/withdrawals`、`GET /api/v1/merchant/settlement/receiver`：仅认证商家主账号（OWNER）可读（失败关闭）。
- `POST /api/v1/merchant/wallet/withdrawals`：**仅限商家 OWNER**，写审计、`X-Request-Id` 幂等、**不设短信验证码**（40 号 §5.5）；运营端无代提现接口（22 号）。

---

## 3. 统一通用规范
- 所有 ID 均为十进制 `String`（Snowflake BIGINT）；所有金额为 `BigDecimal` / `String`（2 位小数，元）；时间均为 UTC ISO-8601。
- 响应信封统一沿用现有后端 `ApiResponse<T>`：`{"success":true,"code":"SUCCESS","message":"OK","data":...,"traceId":"..."}`。
- 所有写接口必填请求头 `X-Request-Id`（幂等键，同键异参返回 `409 IDEMPOTENCY_KEY_CONFLICT`）。
- **严禁事项**（40 号 §8 / 22 号）：不提供任何"线下已打款/标记到账"接口或按钮；不提供商家自助填写/编辑接收方接口；不提供平台自建余额冻结/定向转账。

---

## 4. 运营网页 HTTP 契约（`/api/v1/admin/settlement/**`）

### 4.1 商家结算配置
- `GET /api/v1/admin/settlement/configs/{merchantId}`（权限：`settlement.report.read` 或 `settlement.config.write`）
  - 返回 `SettlementConfigView`：`{ merchantId, settleDays, feeRate, enabled, configured, version, updatedAt }`
- `PUT /api/v1/admin/settlement/configs/{merchantId}`（权限：`settlement.config.write`，`X-Request-Id`）
  - 请求体：`{ settleDays: number(7..30), feeRate: string("0.6000"), enabled: boolean, reason?: string }`
  - 返回 `SettlementConfigView`

### 4.2 分账接收方开通与台账（运营代填，40 号 §4）
- `GET /api/v1/admin/settlement/receivers?merchantId=&status=&page=1&size=20`（权限：`settlement.report.read`）
  - 返回分页 `PageResult<ReceiverAccountSummary>`（默认全部脱敏）
- `GET /api/v1/admin/settlement/receivers/{merchantId}`（权限：`settlement.report.read`）
  - 返回 `ReceiverAccountView`（敏感字段脱敏：`acctNoMasked`、`acctCertificateNoMasked`、`contactMobileMasked`、`licenseNoMasked`）
- `POST /api/v1/admin/settlement/receivers/{merchantId}/reveal`（权限：`settlement.receiver.read-sensitive`）
  - 请求体：`{ reason: string(必填, ≤256) }`
  - 返回 `ReceiverSensitiveView`（仅本次返回明文 `acctNo`、`acctCertificateNo`、`contactMobile`、`licenseNo`，同事务写 `settlement_receiver_audit`）
- `POST /api/v1/admin/settlement/receivers/{merchantId}`（权限：`settlement.receiver.write`，`X-Request-Id`）
  - 保存草稿或提交创建（1084 附件上传 + 1287 同步创建接收方，`settleType="01"` 主动提款）
  - 请求体：`ReceiverUpsertRequest`：
    ```json
    {
      "submitToChannel": true,
      "receiverName": "xxx宠物店",
      "receiverType": "PERSONAL",
      "acctNo": "6222...",
      "acctName": "张三",
      "acctTypeCode": "58",
      "acctCertificateType": "17",
      "acctCertificateNo": "3101...",
      "acctOpenBankCode": "102100099996",
      "acctOpenBankName": "中国工商银行...",
      "acctClearBankCode": "102100099996",
      "contactMobile": "138...",
      "licenseNo": null,
      "licenseName": null,
      "legalPersonName": null,
      "legalPersonCertificateType": null,
      "legalPersonCertificateNo": null,
      "attachments": [
        { "attType": "ID_CARD_FRONT", "assetId": "1001" },
        { "attType": "ID_CARD_BEHIND", "assetId": "1002" },
        { "attType": "BANK_CARD", "assetId": "1003" }
      ],
      "reason": "运营代填开通"
    }
    ```
  - 返回 `ReceiverAccountView`（含 `status`: `DRAFT` / `RECEIVER_APPLYING` / `RECEIVER_CREATED` / `BIND_APPLYING` / `ACTIVE` / `REJECTED` / `CHANGE_APPLYING`、`receiverNo`、`applyId`、`rejectReason`）
- `PUT /api/v1/admin/settlement/receivers/{merchantId}`（权限：`settlement.receiver.write`，`X-Request-Id`）
  - 变更接收方信息（1084 + 1288 同步变更，`settleType="01"`），返回 `ReceiverAccountView`
- `POST /api/v1/admin/settlement/receivers/{merchantId}/bind`（权限：`settlement.receiver.write`，`X-Request-Id`）
  - 发起分账关系绑定（1084 合作协议附件 + 1290 异步绑定，进入 `BIND_APPLYING` 并登记 1872 查单任务）
  - 请求体：`{ cooperationAssetId?: string, remark?: string }`
  - 返回 `ReceiverAccountView`
- `POST /api/v1/admin/settlement/receivers/{merchantId}/sync`（权限：`settlement.receiver.write`）
  - 主动同步拉卡拉 1289 详情 / 1872 绑定审核状态，返回 `ReceiverAccountView`

### 4.3 结算单与分账/回退指令台账
- `GET /api/v1/admin/settlement/orders?merchantId=&status=&page=1&size=20`（权限：`settlement.report.read`）
  - 返回 `SettlementPage<SettlementOrderView>`
- `GET /api/v1/admin/settlement/orders/{settlementOrderId}`（权限：`settlement.report.read`）
  - 返回 `SettlementOrderView`
- `GET /api/v1/admin/settlement/orders/{settlementOrderId}/instructions`（权限：`settlement.report.read`）
  - 返回 `List<SettlementInstructionView>`
- `POST /api/v1/admin/settlement/orders/{settlementOrderId}/resume`（权限：`settlement.instruction.retry`，`X-Request-Id`）
  - 请求体：`{ reason?: string }`，返回 `SettlementOrderView`
- `POST /api/v1/admin/settlement/orders/{settlementOrderId}/retry`（权限：`settlement.instruction.retry`，`X-Request-Id`）
  - 请求体：`{ reason?: string }`，返回 `SettlementOrderView`
- `POST /api/v1/admin/settlement/orders/{settlementOrderId}/retry-fallback`（权限：`settlement.instruction.retry`，`X-Request-Id`）
  - 请求体：`{ refundOrderId: string, reason?: string }`，返回 `SettlementOrderView`

### 4.4 提现台账（运营只读，22 号禁止代提现）
- `GET /api/v1/admin/settlement/withdrawals?merchantId=&status=&page=1&size=20`（权限：`settlement.report.read`）
  - 返回分页 `PageResult<WithdrawalOrderView>`：
    `{ withdrawalId, merOrderNo, merchantId, receiverNo, amount, feeAmount, netAmount, status, drawJnl, failReason, channelState, requestedByUserId, requestedAt, finishedAt, createdAt }`

### 4.5 平台补贴应付报表（只读，40 号 §3.2 严禁标记已打款）
- `GET /api/v1/admin/settlement/subsidy-report?merchantId=&fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd&page=1&size=20`（权限：`settlement.report.read`）
  - 返回 `SubsidyReportResponse`：
    ```json
    {
      "summary": {
        "totalOrderCount": 12,
        "totalMerchantShare": "1192.80",
        "totalSplitAmount": "956.40",
        "totalSubsidyDue": "236.40"
      },
      "items": [
        {
          "merchantId": "5001",
          "period": "2026-11",
          "orderCount": 12,
          "totalPaidAmount": "960.00",
          "totalMerchantShare": "1192.80",
          "totalSplitAmount": "956.40",
          "totalReversedAmount": "0.00",
          "totalSubsidyDue": "236.40"
        }
      ],
      "total": 1,
      "page": 1,
      "size": 20
    }
    ```

### 4.6 日对账差异与异常处理（不自动改账）
- `GET /api/v1/admin/settlement/reconcile-diffs?reconcileDate=&diffType=&status=&page=1&size=20`（权限：`settlement.report.read`）
  - 返回分页 `PageResult<ReconcileDiffView>`：
    `{ diffId, reconcileDate, bizType, diffType, settlementOrderId, instructionId, outSeparateNo, separateNo, merchantId, localAmount, channelAmount, localStatus, channelStatus, detail, status, resolvedByOperatorId, resolveNote, resolvedAt, createdAt }`
- `POST /api/v1/admin/settlement/reconcile-diffs/{diffId}/resolve`（权限：`settlement.instruction.retry`，`X-Request-Id`）
  - 请求体：`{ note: string(必填, ≤256) }`（仅标记人工已核销处理，绝不自动改账）

---

## 5. 小程序商家端 HTTP 契约（`/api/v1/merchant/**`，仅 OWNER）

- `GET /api/v1/merchant/settlement/receiver`（只读，40 号 §7）
  - 返回 `MerchantReceiverStatusView`：
    `{ status, statusLabel, receiverNo, acctNameMasked, acctNoLast4, bankName, active, rejectReason, updatedAt }`
    （未配置时返回 `status: "UNCONFIGURED"`, `active: false`；商家不可自助填写或修改）
- `GET /api/v1/merchant/wallet`（余额以拉卡拉 1269 `curBalance` 为准；40 号 §5.5 / §9 不展示平台补贴待结算）
  - 返回 `MerchantWalletView`：
    `{ receiverStatus, receiverActive, availableBalance, pendingSettlementAmount, pendingSettlementCount, currency: "CNY", balanceAsOf, channelBalanceAvailable }`
- `GET /api/v1/merchant/wallet/withdrawals?status=&page=1&size=20`
  - 返回分页 `PageResult<WithdrawalOrderView>`
- `POST /api/v1/merchant/wallet/withdrawals`（仅 OWNER，`X-Request-Id` 必填，无短信验证码）
  - 请求体：`{ amount: string("100.00") }`
  - 返回 `WithdrawalOrderView`（状态 `PROCESSING` / `SUCCEEDED` / `FAILED`）

---

## 6. 公开拉卡拉异步回调路由（独立安全链，按 1009 验签）

全部只接收 `POST` + JSON，先按 `LAKALA_NOTIFY_CERT_PATH` 验签，验签通过并落库后返回 `{"code":"SUCCESS","message":"执行成功"}`：
- `POST /api/v1/callbacks/lakala/pay`（1112 交易通知 → `PaymentCallbackRouter`）
- `POST /api/v1/callbacks/lakala/separate`（1297 分账/回退结果通知 → `SettlementReceiptWriter`）
- `POST /api/v1/callbacks/lakala/withdrawals`（1272 提现结果通知 → `WalletReceiptWriter`）
- `POST /api/v1/callbacks/lakala/ledger`（1290 分账关系绑定审核回调 → `ReceiverAccountService`）

---

## 7. 配置收口（遵循 PR #234 单开关架构，不新增子开关）

阶段 2～8 的分账结算、接收方管理、商家钱包提现、日对账与补偿扫描、以及运营/商家/拉卡拉回调 HTTP 路由**全部归入唯一支付底座总开关 `pet.payment.enabled`**（`application.yml` 默认 `false` 供隔离单测，`application-dev/test/prod.yml` 统一 `true`），不再拆分子开关：

- 运营端控制器 `/api/v1/admin/settlement/**` 随 `pet.auth.admin.enabled=true` 且 `pet.payment.enabled=true` 装配；
- 商家端控制器 `/api/v1/merchant/{settlement/receiver,wallet,wallet/withdrawals}` 随 `pet.auth.c.enabled=true` 且 `pet.payment.enabled=true` 装配；
- 拉卡拉 4 条公开回调 `/api/v1/callbacks/lakala/{pay,separate,withdrawals,ledger}` 随 `pet.payment.enabled=true` 装配；
- 仅通过环境变量/配置参数控制凭据与行为参数：`SETTLEMENT_RECEIVER_AES_KEY_PATH`（缺配启动 fail-closed）、`LAKALA_ORG_CODE`、`LAKALA_SPLIT_LOWEST_RATIO`、`LAKALA_SEPARATE_NOTIFY_URL`、`LAKALA_LEDGER_NOTIFY_URL`、`LAKALA_WITHDRAW_NOTIFY_URL`、`pet.payment.lakala.settle-type`（`0` 普通收单 / `1` 分账收单）。
