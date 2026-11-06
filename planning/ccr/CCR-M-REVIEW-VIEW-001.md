# CCR-M-REVIEW-VIEW-001 商家评价读侧补买家昵称与评分聚合

- 状态：APPROVED（2026-11-06 用户批准；裁决记录见 §9）
- 日期：2026-11-06
- 提出方：商家端 Figma 还原（Claude Code）
- 关联：Figma `23:14160`（店铺评价-全部）、`68:2106/68:3178/68:4048`；
  Contract 56、Contract 06 §10、Contract 70（reviewAvg）；PR #211；PLAN §19/§20
- 当前 Contract：`docs/04-api/56-Review-Appeal-Contract-v0.1.md` “MerchantReviewSummary”
- 发现的问题：评价列表只有星级/三维分/正文/日期/申诉状态，无法显示买家昵称/头像，
  也没有原稿的评分聚合卡与星级直方图（总评价/待回复/好评率/申诉中）
- 是否已有 SSOT/PRD 支撑：SSOT §11.1/§11.2 已定义评分与不计分规则；买家身份与
  聚合展示未明确
- 是否涉及产品裁决：**YES**（买家身份展示）；聚合口径需 Contract Owner 确认

## 1. 背景与现状

56 号 MerchantReviewSummary 固定字段：

```text
reviewId, orderId, storeScore, serviceScore, staffScore, compositeScore,
scoreIncluded, visibilityStatus, content, createdAt, appealStatus, appealId
```

详情再加申诉字段。列表没有买家昵称/头像，也没有门店级评分聚合。

Figma `23:14160` 的聚合区包含：综合评分大卡、星级直方图、总评价/待回复/好评率/
申诉中统计、筛选 chips；卡片含买家头像/昵称（图片/有帮助/回复属后续能力）。
当前页面按登记偏差只渲染评价卡。

## 2. 数据可用性核实

| 提议字段 | 数据来源 | 现状 |
|---|---|---|
| reviewerNickname | `user_account.nickname`（经 `review.user_id`） | 有数据，未投影 |
| reviewerAvatarUrl | `user_account.avatar_url`（可空） | 有数据，未投影 |
| averageScore | `review.composite_score` 均值（`score_included=1`） | 有内部窗口聚合，无门店全量读面 |
| scoreCounts[1..5] | `review.composite_score` 分桶计数（`score_included=1`） | 需新增只读聚合 |
| totalReviews | `review` 行数（本店） | 需新增只读聚合 |
| 评价图片 | `review_media` 表存在 | **C 端评价媒体上传未开放（REV-001 明确）**，V1 无数据 |
| 待回复/回复 | — | 评价回复不是 SSOT §11.3 已批能力，不做 |

## 3. 最小变更提议（分两部分）

### 3.1 买家身份（需产品批准）

MerchantReviewSummary/Detail 增量追加：

```json
{
  "reviewerNickname": "李**",
  "reviewerAvatarUrl": "https://…"
}
```

- 昵称是否脱敏由产品决定（推荐：原昵称，null 时前端显示“匿名用户”）；头像可空。
- 仅 OWNER 本店读侧返回；HIDDEN 评价仍对 OWNER 可见，字段范围不变。
- 不返回 userId/手机号/订单客户 ID。

### 3.2 评分聚合（只读，新增一条 M 端路由）

```text
GET /api/v1/merchant/reviews/summary?merchantId&storeId
data: {
  "totalReviews": 12,
  "scoredCount": 10,
  "averageScore": "4.6",
  "scoreCounts": { "1": 0, "2": 1, "3": 1, "4": 3, "5": 5 }
}
```

- 口径：
  - `scoredCount`/`averageScore`/`scoreCounts` 只统计 `score_included=1`（SSOT §11.2
    已核销后部分退款不计入商家评分）；
  - `scoreCounts` 按 `ROUND(composite_score)` 落 1..5 星桶（需 Contract Owner 确认；
    备选：按 0.5 星区间分桶，UI 不支持半星则取整）；
  - `averageScore` 一位小数，服务端四舍五入，前端只展示不重算（ARCH-005）；
  - `totalReviews` 含 `score_included=0` 的历史评价，仅用于“总评价”文案。
- 只读、OWNER + 门店 guard、`no-store`；单条索引查询（`store_id, score_included,
  created_at` 已存在），无新表/事件。
- 前端聚合卡/直方图/统计卡按该响应渲染；“待回复/好评率”若无对应事实则不显示
  （不伪造 0）。

## 4. 隐私与安全

- 买家昵称/头像属公开评价的展示信息，但仍只在本店 OWNER 读侧返回，不进入 C 端匿名面。
- 不返回 userId/手机号；日志/审计不记录昵称。
- 聚合不返回任何个体评价内容。

## 5. 兼容性与影响

- API：纯增量字段 + 一条新只读路由；旧客户端忽略。
- Schema/Event：无变化。
- 前端影响：评价列表卡（昵称/头像）、评分聚合卡/直方图/统计；严格解码器放宽。
- 测试影响：聚合分桶/不计分排除/空店（0 条返回 0 与 null 均值）/跨店 403；
  昵称/头像空值降级。

## 6. 验收标准

1. 评价列表显示买家昵称（或“匿名用户”）与可空头像；跨店 403 不泄漏。
2. 聚合接口返回 total/scored/平均分/五档计数；`score_included=0` 不进入均值与直方图。
3. 空店返回 `totalReviews=0, scoredCount=0, averageScore=null, scoreCounts` 全 0。
4. 既有申诉读写、幂等与防枚举零变化。

## 7. 不做范围（明确）

- 评价图片展示：`review_media` 存在但 C 端媒体上传未开放，V1 无数据；待 C 端媒体
  切片另行立项。
- 商家回复/有帮助：未列入 SSOT §11.3，不新增。
- 评价筛选 chips 的服务端过滤：本 CCR 只加聚合，不新增筛选参数。
- C 端公开评价列表/评分展示：另行切片。

## 8. 待裁决项

1. 是否展示买家昵称/头像？昵称是否脱敏？
2. 聚合分桶采用 `ROUND(composite_score)`（推荐）还是其他规则？
3. 聚合路由独立（推荐）还是塞进列表响应？
4. 评价图片/回复确认不做（推荐），还是另立 CCR？

## 9. 裁决记录（2026-11-06，用户）

1. 展示买家昵称**原样**（不脱敏；null 时前端显示「匿名用户」）+ 头像可空。
2. 分桶采用 `ROUND(composite_score)`。
3. 聚合路由独立（`GET /merchant/reviews/summary`）。
4. 评价图片/回复确认不做。

契约同步：56号 §7 补充 + OpenAPI11 MerchantReviewSummaryData//merchant/reviews/summary；实施随 PR #211。
