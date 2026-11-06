# CCR-M-REFUND-VIEW-001 商家退款读侧补客户/申请说明/时间线

- 状态：PROPOSED（待产品 + 安全裁决；未实现）
- 日期：2026-11-06
- 提出方：商家端 Figma 还原（Claude Code）
- 关联：Figma `9:779`（退款列表）、`9:891`（退款详情）；
  Contract 57、Contract 49、Contract 06 §10；PR #211；PLAN §15/§20
- 当前 Contract：`docs/04-api/57-Merchant-Refund-Application-Contract-v0.1.md`
  “回执与读侧字段”
- 发现的问题：商家在 24 小时处理窗口内只能看到申请编号/原因编码/金额/状态/期限，
  看不到客户、申请说明、时间线；Figma 的客户信息/申请正文/服务流程块无法渲染
- 是否已有 SSOT/PRD 支撑：49 号明确“买家申请说明明文与买家身份字段不回传商家”
  （密文留存）——本 CCR 是**对该既定隐私口径的变更请求**，必须产品 + 安全共同裁决
- 是否涉及产品裁决：**YES**

## 1. 背景与现状

57 号读侧 Summary/Detail 固定字段：

```text
applicationId, applicationNo, orderId, status, applicationVersion,
reasonCode, refundAmount, createdAt, merchantDeadline,
decidedAt, decisionId, refundOrderId
```

明确不回传：买家申请说明明文、买家身份（userId/手机号）。

存储事实（49 号 Schema）：

- `refund_application.reason_text_cipher VARBINARY(4096)`，且有
  `chk_refund_application_plaintext CHECK (reason_text IS NULL AND merchant_decision_reason IS NULL)`；
  申请说明只以密文存在。
- `refund_application_decision.reason_cipher` 存商家拒绝理由（密文）。
- **49 号没有退款申请凭证/图片存储**（与售后 50/51 号不同）；Figma 的图片区没有数据来源。
- 订单状态日志 `order_status_log` 有 `REFUND_APPLICATION` 维度，可用于只读时间线。

## 2. 数据可用性核实

| 提议字段 | 数据来源 | 现状 |
|---|---|---|
| customerNickname | `user_account.nickname`（经订单 user_id） | 有数据，未投影 |
| customerPhoneMasked | `user_account.phone` 脱敏 | 有数据，未投影；本 CCR 默认不包含 |
| reasonText（申请说明） | `refund_application.reason_text_cipher` 解密 | 密文存在；需新增授权解密端口 |
| serviceName | `order_service_snapshot.service_name` | 有数据，未投影 |
| timeline[] | `refund_application` 创建/决定 + `order_status_log` REFUND_APPLICATION | 有数据，未投影 |
| evidenceImages | — | **无存储、无上传，V1 不提议** |

## 3. 最小变更提议（分三档，可分别批准）

### 3.1 低风险：客户与服务的脱敏投影（推荐先批）

在 57 号 Summary/Detail 增量追加：

```json
{
  "customerNickname": "E2E体验用户",
  "customerPhoneMasked": "138****0100",
  "serviceName": "E2E到店精细洗护"
}
```

- 昵称可空；手机号仅脱敏串，永不回明文；服务名来自订单快照。
- 不改 49 号存储，不改决定/退款语义；读侧仍是 OWNER + 门店坐标。

### 3.2 中风险：申请说明解密展示（需产品 + 安全批准）

在 57 号 Detail（列表不需要）追加：

```json
{ "reasonText": "服务完成后发现毛发修剪不齐…" }
```

- 实现方式：refund-biz 在**同一只读事务、既有 OWNER 重验之后**调用 49 号解密端口
  （密钥材料归属 payment/refund 域；不跨模块读 Mapper，不把密文/密钥出域）。
- 仅 Detail 返回；列表不返回，减少暴露面；日志不记录明文。
- 这是对 49 号“不回传商家”条款的反转，必须明确批准；若不批准，保留 `reasonCode`
  现状即可（商家仍可批准/拒绝，但依据有限）。

### 3.3 低风险：只读时间线

```json
{
  "timeline": [
    { "stage": "APPLIED", "occurredAt": "2026-11-05T08:15:32.649Z" },
    { "stage": "MERCHANT_DEADLINE", "occurredAt": "2026-11-06T08:15:32.649Z" }
  ]
}
```

- 仅由已有时间戳派生（申请创建、商家期限、决定时间、退款单创建等），不新增表。
- 不包含客户/内部备注；不推导 DisplayOrderStatus（order 域统一计算）。

### 3.4 不提议：凭证图片

49 号没有退款凭证存储；新增需要新的上传/存储/审核/读取授权链，属新产品能力，
不在本 CCR 范围。Figma 图片区继续登记为设计偏差。

## 4. 隐私与安全

- 3.1/3.3 只在 OWNER 本店作用域返回，跨店/非 OWNER 同一 403；响应 `no-store`。
- 3.2 是敏感反转：需明确解密授权端口、只读事务、最小返回面（仅详情）、审计不落明文、
  日志脱敏；拒绝理由仍走 49 号既有密文，不回显给客户。
- 不返回 userId、明文手机号、地址、健康字段。

## 5. 兼容性与影响

- API：纯增量字段（3.1/3.3）；3.2 为条件性新增。
- Schema/Event：无变化；3.2 仅新增读取端口/装配。
- 前端影响：退款列表/详情卡（客户/服务/说明/时间线）；严格解码器需同步放宽。
- 测试影响：OWNER/跨店/空昵称/无服务快照；3.2 解密失败 503 失败关闭、明文不出日志；
  3.3 时间线排序与空值；既有决定流程不变。

## 6. 验收标准

1. 3.1：列表/详情显示客户昵称（或占位）与脱敏手机号、服务名；跨店 403 不泄漏。
2. 3.2（若批准）：详情显示申请说明明文；解密依赖缺失 503；日志/审计无明文。
3. 3.3：详情时间线按发生时间升序；无额外查询表。
4. 商家同意/拒绝决定语义、24 小时期限、幂等重放全部不变。

## 7. 备选方案

- 备选 A：只做 3.1 + 3.3，不做 3.2（保守，商家仍看不到申请说明）。
- 备选 B：维持现状，只登记 Figma 偏差（最保守）。
- 备选 C：新增退款凭证上传/展示（超出本 CCR，需独立产品立项）。

## 8. 待裁决项

1. 是否批准 3.1 客户昵称/脱敏手机号/服务名？
2. 是否批准 3.2 向 OWNER 解密展示申请说明？若批准，解密端口归属与审计口径？
3. 是否批准 3.3 只读时间线？
4. 凭证图片确认不做（推荐），还是另立 CCR？
