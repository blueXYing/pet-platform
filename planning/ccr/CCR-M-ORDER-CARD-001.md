# CCR-M-ORDER-CARD-001 商家订单列表卡片补客户/服务/宠物投影

- 状态：PROPOSED（待产品裁决；未实现）
- 日期：2026-11-06
- 提出方：商家端 Figma 还原（Claude Code）
- 关联：Figma `95:1899`（工作台待办预约）、`23:14926`（订单 tab 族）；
  Contract 10 §4.1；PR #211；`planning/progress/2026-11-04/merchant-figma-restore/PLAN.md` §8/§14/§20
- 当前 Contract：`docs/04-api/10-HTTP-API-Contract-v0.4.md` §4.1
  （`GET /api/v1/merchant/orders`）；读侧 Summary 恰 7 键
- 发现的问题：商家订单列表/工作台待办只能显示订单号尾号、金额、时间；Figma 的
  客户姓名/头像、服务名、宠物信息无法渲染
- 是否已有 SSOT/PRD 支撑：未检索到明确条款（需产品确认展示口径）
- 是否涉及产品裁决：**YES**（客户/宠物信息在商家读侧的展示范围）

## 1. 背景与现状

商家订单列表 HTTP 面已落地（`MerchantOrderController`，开关
`pet.order.merchant.http.enabled` 默认关闭），响应为 PageResult 信封，列表项固定：

```json
{
  "orderId": "112003538795720704",
  "orderNo": "112003538795720705",
  "displayStatus": "PENDING_CONFIRM",
  "payAmount": "60.00",
  "appointmentStart": "2026-11-06T06:00:00.000Z",
  "appointmentEnd": "2026-11-06T07:00:00.000Z",
  "paidAt": "2026-11-06T01:52:06.000Z"
}
```

前端 `frontend-miniapp/src/merchant/order/model.ts:192` 的 `MerchantOrderSummary`
严格校验 7 键；工作台待办卡与订单列表因此只有「订单尾号 + 金额 + 时间 + 倒计时」。

Figma `95:1899` 的待办预约卡与 `23:14926` 的订单卡包含：客户头像/姓名、服务名、
宠物（名字/品种），这是当前视觉差距的主要来源之一。

## 2. 数据可用性核实

| 提议字段 | 数据来源 | 现状 |
|---|---|---|
| customerNickname | `user_account.nickname`（经 `pet_order.user_id`） | 已有数据，未投影 |
| customerAvatarUrl | `user_account.avatar_url`（可空） | 已有数据，未投影 |
| serviceName | `order_service_snapshot.service_name`（下单快照） | 已有数据，未投影 |
| petName / petType / petBreed | `order_pet_snapshot.pet_name/pet_type/breed_name` | 已有数据，未投影 |
| customerPhoneMasked | `user_account.phone` 脱敏 `138****0100` | 已有数据，**本 CCR 不默认包含** |

以上均在同一订单域可读的既有表，无需新增 Schema/Event；`order_service_snapshot` 与
`order_pet_snapshot` 都是下单时不可变快照，展示口径不会随服务/宠物后续编辑漂移。

## 3. 最小变更提议

在 `GET /api/v1/merchant/orders` 的每个 item **增量追加可空字段**（不改变既有 7 键）：

```json
{
  "customerNickname": "E2E体验用户",
  "customerAvatarUrl": null,
  "serviceName": "E2E到店精细洗护",
  "petName": "豆豆",
  "petType": "DOG",
  "petBreed": "柯基"
}
```

- 查询仍在既有 `merchantId + storeId` 门店 guard 与 OWNER 只读重验之后执行，跨店行
  不可见；分页、排序、displayStatus 过滤、错误映射全部不变。
- 空值语义：客户未设置昵称/头像、订单无宠物快照时返回 `null`，不伪造占位。
- 手机号默认不返回。若产品要求「联系客户」，只允许新增 `customerPhoneMasked`
  （服务端脱敏，永不回明文），并单独评审；本 CCR 不预置该字段。
- 不改工作台 `/merchant/dashboard` 聚合接口；工作台直接复用订单列表。

## 4. 隐私与安全

- 读侧已是 OWNER + 门店坐标作用域；客户信息仅在本店订单上下文展示，不跨店聚合。
- 头像 URL 来自 `user_account.avatar_url`（微信头像外链）；若产品认为外链不可控，
  可退化为仅返回昵称首字，由前端用既有头像占位。
- 不返回手机号明文、地址、健康备注；宠物只返回名字/类型/品种级字段，不返回体重、
  疫苗、健康备注。
- 日志/审计不记录客户信息；前端不落本地持久化。

## 5. 兼容性与影响

- API：纯增量字段；旧客户端忽略未知键即可。前端严格解码器需同步放宽（本 PR 已按
  “恰好 7 键”校验，实施时必须一起改，否则真实列表会判 INVALID_RESPONSE）。
- Schema：无变化。
- Event：无变化。
- 前端影响：`merchant/order/model.ts`、工作台待办卡、订单列表卡；其余页面不动。
- 测试影响：
  - 后端：列表新增字段、空值、跨店 403 不变；可选筛选/分页回归。
  - 前端：解码器新增字段回归；旧 7 键响应仍可解码（向后兼容）。
  - 隐私：断言响应中没有 phone 明文/健康字段。

## 6. 验收标准

1. 真实门店订单列表返回新增字段；无昵称/无宠物订单返回 null 且页面正常降级。
2. 跨店/非 OWNER 仍是同一 403，响应不泄漏任何客户字段。
3. 工作台待办卡显示客户昵称（或首字占位）、服务名、宠物名；订单列表卡片同步。
4. 后端契约测试 + 前端解码回归 + `check-persistence-style.py` 通过。

## 7. 备选方案

- 备选 A：不改 HTTP，工作台/订单列表只显示订单号（现状），Figma 客户/宠物块登记为
  契约缺口不还原。
- 备选 B：新增独立的订单卡片聚合接口，避免扩展现有 Summary；成本更高，且与列表
  分页/权限重复，不推荐。

## 8. 待裁决项

1. 是否允许商家订单读侧展示客户昵称与头像？（推荐：允许昵称；头像可空）
2. 是否展示服务名与宠物名字/品种？（推荐：允许，均为下单快照）
3. 是否需要 `customerPhoneMasked`？（推荐：不需要；如需要仅脱敏且单独评审）
4. 若批准，是否接受本 CCR 的纯增量字段方案（推荐）？
