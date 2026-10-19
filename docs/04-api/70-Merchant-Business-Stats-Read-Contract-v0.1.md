# 商家经营统计读侧契约 v0.1（70号）

状态：ACCEPTED_CONTRACT / IMPLEMENTED_DEFAULT_OFF（`pet.order.stats.http.enabled`，2026-10-19 用户裁决"全做"范围内按最小实现执行）。来源：M 端 PRD 5.14 经营数据中心 + 审计 C1 #13/#14（帧 5:3759 / 143:5192 同稿两稿）。

## 1. 口径总则

- 单店维度（merchantId+storeId 双键），全部指标从 ORDER 域既有 `pet_order` / `order_pet_snapshot` 读侧聚合 + REVIEW 域既有 `review` 读侧投影，**零新事实源**；金额 BigDecimal/两位小数字符串；无写入路径。
- 周期词汇：`TODAY` / `LAST_7D` / `LAST_30D`（PRD 5.14 快捷区间；默认 LAST_7D）。**自定义区间不做**（CCR 登记）；环比 = 等长紧邻前一周期同口径聚合。
- 统计时间锚：订单归属周期用 `created_at`；营收归属周期用 `verified_at`（核销完成时间）。服务时区为 UTC（存储口径），页面按 UTC 日期桶展示。
- PRD 5.14"数据按 T+1 更新"为报表仓库口径；V1 为实时读侧聚合（更保守、无迟到数据），如实披露偏差，非 T+1 承诺。
- "接口异常或未取到数据时显示 --"：`reviewAvg` 在评价内核未装配时为 null（页面显示 --，装配默认关闭语义，非 fail-open——装配后读失败即 503）。数值正常为 0 时返回 0。

## 2. 指标定义（全部可从既有事实重算）

| 指标 | 定义 |
|---|---|
| orderCount 订单量 | 周期内创建的订单数（含已取消，PRD 口径） |
| paidOrderCount 支付订单数 | 周期内创建且 paid_at 非空的订单数 |
| verifiedOrderCount 核销完成数 | 周期内创建且 verified_at 非空的订单数 |
| revenue 净营收 | 周期内创建订单的 Σ max(pay_amount − refunded_amount, 0)（PRD"净营收扣除已成功退款；全额退款订单不计入（净额为 0）；部分退款按净额计入"；第三方团购 V1 无事实源） |
| avgTicket 客单价 | revenue / verifiedOrderCount（分母 0 → null；PRD：营收/已核销订单数） |
| verifyRate 核销率 | verifiedOrderCount / paidOrderCount（PRD"反映履约完成度"在订单创建周期锚下的可计算化：进入已支付履约流程的订单中核销完成占比），百分数保留 1 位小数字符串；分母 0 → null |
| refundOrderCount 退款订单数 | 周期内创建且 refunded_amount > 0 的订单数 |
| refundRate 退款率 | refundOrderCount / paidOrderCount（PRD：退款订单数/已支付订单数），百分数 1 位小数；分母 0 → null |
| newCustomers 新增客户 | 周期内创建首个本店订单的去重客户数（MIN(created_at) 落在周期内的 user_id 数） |
| repeatRate 复购率 | 周期内下单 ≥2 次客户数 / 周期内下单客户数，百分数 1 位小数；分母 0 → null |
| reviewAvg 评价均分 | 周期内新增、score_included=1 的 review 的 composite_score 均值（PRD：仅统计有效完成订单评价），0.0-5.0 保留 1 位小数字符串；周期内无评价 → null；评价内核未装配 → null |
| 拒单率 / 排期利用率 / 服务分析下钻 / 客户消费分层 | **V1 不做**（拒单事实散在事件/日志无单店聚合计数字段，排期利用率需 53号窗口占用事实聚合——均 CCR 登记待裁，见 §6） |

比率字段为字符串（如 `"83.3"`，无 % 号）；null 表示不可计算（页面显示 --）。环比 delta 百分数 =（本期−上期）/上期×100 保留 1 位，上期为 0 或指标为 null → null。

## 3. HTTP（10号 §4.9 同步）

MINIAPP Bearer + OWNER 坐标复验（同 10号 §4.1/69号：`requireOwnerRead` 于门店 guard 事务内，ACTIVE/OFFLINE/FROZEN 存量读取家族；越权/不存在 403 同形防枚举）；`Cache-Control: no-store`；只读无 X-Request-Id；400 坐标/range/limit 非法；401/403/503 同 69号；无新增错误码。

| 方法/路径 | 请求 | data |
|---|---|---|
| GET `/merchant/stats/overview` | merchantId、storeId 必填；range? 枚举（默认 LAST_7D） | `{range, revenue, orderCount, newCustomers, avgTicket, verifiedOrderCount, verifyRate, refundOrderCount, refundRate, repeatRate, reviewAvg, deltas:{同键环比百分数或 null}, generatedAt}` |
| GET `/merchant/stats/services/top` | merchantId、storeId；range?；limit? 1..5 默认 5 | `{range, items:[{serviceId, serviceName, orderCount, revenue}]}`（周期内创建订单按 service_id 聚合，serviceName 取该服务最新快照名；按 orderCount 降序、serviceId 升序） |
| GET `/merchant/stats/revenue-trend` | merchantId、storeId；range? | `{range, buckets:[{date:"YYYY-MM-DD", revenue, orderCount}]}`（按日闭区间；TODAY 为单日桶） |

generatedAt 为毫秒精度带偏移 ISO 字符串（服务端聚合完成时刻）。

## 4. 架构归属

ORDER 域拥有统计投影（聚合 SQL 在 pet-order-biz 自有 Mapper XML，走 `idx_order_store_stage_created` 等既有索引，禁全表扫）；评价均分经 review 域新增只读面 `ReviewQueryApi.storeScoreSummary`（review-biz 自有 XML，`idx_review_store_score` 既有索引），boot 侧以可选端口注入（评价内核未装配 → reviewAvg=null）；OWNER 复验经既有 `MerchantOrderAuthorityApi`。无跨模块持久层访问（ARCH-002）。

## 5. 验收要点

- 金额/比率断言：两位小数金额字符串、1 位小数比率字符串、null 语义（分母 0/无评价/上期为 0）。
- 周期断言：LAST_7D=近 7 个 UTC 日（含今日）减 7 天锚点起；环比窗口等长紧邻。
- 越权断言：非本店坐标 403 同形；未知 range/limit 400。
- 性能：聚合语句必须命中 store 前导索引；测试以 EXPLAIN 断言非全表扫（或以覆盖索引扫描计划断言）。

## 6. CCR 登记待裁（本切片不做）

1. 拒单率（需单店拒单聚合计数字段或日志聚合裁决）；
2. 排期利用率（需 53号窗口占用事实的聚合口径裁决）；
3. 服务分析下钻明细列表、客户消费分层、自定义时间区间；
4. T+1 报表仓库口径与首页看板 hero 数据区回补（工作台数据区渲染属工作台切片）。
