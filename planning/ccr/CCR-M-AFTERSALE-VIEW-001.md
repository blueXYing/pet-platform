# CCR-M-AFTERSALE-VIEW-001 商家售后读侧补客户昵称与服务/宠物快照

- 状态：PROPOSED（待产品裁决；未实现）
- 日期：2026-11-06
- 提出方：商家端 Figma 还原（Claude Code）
- 关联：Figma `40:1061`（售后工单列表）、`40:1345`（售后详情）；
  Contract 51、Contract 06 §10；PR #211；PLAN §17/§20
- 当前 Contract：`docs/04-api/51-AfterSale-Http-Contract-v0.1.md`
  “CaseView 字段由 OpenAPI 的 AfterSaleCaseDetail 完整定义”
- 发现的问题：商家售后列表/详情只能显示工单号、订单号、问题类型、诉求金额与状态；
  无法显示客户与本次服务/宠物，工单识别成本高
- 是否已有 SSOT/PRD 支撑：51 号明确 CaseView “不含用户电话、内部敏感备注、渠道事实”；
  昵称/服务/宠物未明确禁止，但属新增展示面
- 是否涉及产品裁决：**YES**（客户信息展示边界）

## 1. 背景与现状

51 号 CaseView 已包含：工单状态、订单号、问题类型、诉求、申请说明、补证要求、
双方证据批次、终裁原因；明确不包含用户电话、内部敏感备注、渠道事实。

商家售后页（`merchant/pages/aftersale`）因此只能显示工单号/订单号/类型/金额/状态；
Figma 的客户头像/姓名、服务项目、宠物信息块无法渲染。

## 2. 数据可用性核实

| 提议字段 | 数据来源 | 现状 |
|---|---|---|
| customerNickname | `user_account.nickname`（经订单 user_id） | 有数据，未投影 |
| serviceName | `order_service_snapshot.service_name` | 有数据，未投影 |
| petName / petType / petBreed | `order_pet_snapshot.pet_name/pet_type/breed_name` | 有数据，未投影 |
| customerPhoneMasked | `user_account.phone` 脱敏 | 有数据；**本 CCR 默认不包含** |

售后工单本身已有 `order_id`，订单快照是下单时不可变事实，不存在跨店漂移。

## 3. 最小变更提议

在 51 号 CaseView（列表/详情同形）**增量追加可空字段**：

```json
{
  "customerNickname": "E2E体验用户",
  "serviceName": "E2E到店精细洗护",
  "petName": "豆豆",
  "petType": "DOG",
  "petBreed": "柯基"
}
```

- 读取仍在既有 M 端 OWNER + 门店 guard 之后；跨店/非本人工单维持 404/403 防枚举。
- 空值：无昵称/无宠物快照返回 null，前端降级为工单号/服务名占位。
- **不返回用户电话**（保持 51 号既有隐私红线）；若产品要求联系客户，另议
  `customerPhoneMasked`（脱敏、仅详情），本 CCR 不预置。
- 证据、补证、决定流程、状态机、版本全部不变。

## 4. 隐私与安全

- 客户昵称/宠物信息仅在本店售后上下文展示，不进入全局搜索/导出。
- 不返回 userId、明文手机号、地址、健康备注、内部敏感备注。
- 响应 `Cache-Control: no-store`；日志/审计不记录客户信息。
- HIDDEN/INVALIDATED 等终局工单仍可回看，但字段范围不变。

## 5. 兼容性与影响

- API：纯增量字段；旧客户端忽略未知键。
- Schema/Event：无变化。
- 前端影响：售后列表卡、详情首卡；严格解码器同步放宽。
- 测试影响：OWNER/跨店防枚举；无快照/空昵称降级；证据/补证/决定回归不变。

## 6. 验收标准

1. 真实工单列表/详情显示客户昵称（或占位）、服务名、宠物名/品种。
2. 无快照工单返回 null 且页面正常降级。
3. 跨店/非 OWNER 维持同一防枚举响应，不泄漏任何客户/服务字段。
4. 既有证据读取、补证、平台裁决链路零变化。

## 7. 备选方案

- 备选 A：只加 `serviceName`（不涉及客户信息，风险最低）。
- 备选 B：维持现状，Figma 客户/宠物块登记为契约缺口。

## 8. 待裁决项

1. 是否允许商家售后读侧展示客户昵称？（推荐：允许）
2. 是否展示服务名与宠物名字/品种？（推荐：允许）
3. 是否保持“不返回手机号”的 51 号红线？（推荐：保持）
