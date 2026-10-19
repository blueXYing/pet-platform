# 商家经营统计读侧契约 v0.1（70号）

状态：ACCEPTED_CONTRACT / IMPLEMENTED_DEFAULT_OFF（`pet.order.stats.http.enabled`，2026-10-19 用户裁决"全做"范围内按最小实现执行）。**2026-10-19 二次扩展（用户裁决"继续扩展CCR"）落地 §6 原登记项 1/2/3**：拒单率（订单域 `cancel_reason` 事实聚合）、排期利用率（53号排期读侧聚合，经 schedule-api 新只读端口）、服务分析下钻明细 + 自定义时间区间（from/to ≤92 天窗）。来源：M 端 PRD 5.14 经营数据中心 + 审计 C1 #13/#14（帧 5:3759 / 143:5192 同稿两稿）。

## 1. 口径总则

- 单店维度（merchantId+storeId 双键），全部指标从 ORDER 域既有 `pet_order` / `order_pet_snapshot` 读侧聚合 + REVIEW 域既有 `review` 读侧投影 + SCHEDULE 域既有 `schedule_availability_window` / `schedule_reservation` 读侧投影，**零新事实源**；金额 BigDecimal/两位小数字符串；无写入路径。
- 周期词汇：`TODAY` / `LAST_7D` / `LAST_30D`（PRD 5.14 快捷区间；默认 LAST_7D）+ **`CUSTOM`（2026-10-19 扩展）**：query 携 `from`/`to`（ISO 日期 `YYYY-MM-DD`，UTC 闭区间 [from 00:00, to+1d 00:00)），对齐既有 UTC 日桶；护栏 `to−from+1 ≤ 92` 天（防大窗全表扫，超限/倒序/格式非法 400）；环比 = 等长紧邻前一周期同口径聚合（CUSTOM 前窗为 [from−len, from)）。
- 统计时间锚：订单归属周期用 `created_at`；营收归属周期用 `verified_at`（核销完成时间）。服务时区为 UTC（存储口径），页面按 UTC 日期桶展示。
- PRD 5.14"数据按 T+1 更新"为报表仓库口径；V1 为实时读侧聚合（更保守、无迟到数据），如实披露偏差，非 T+1 承诺。
- "接口异常或未取到数据时显示 --"：`reviewAvg` 在评价内核未装配时为 null（页面显示 --，装配默认关闭语义，非 fail-open——装配后读失败即 503）；`utilizationRate` 在排期利用率端口未装配时连同 availableMinutes/bookedMinutes 一并为 null（同语义，装配后读失败即 503）。数值正常为 0 时返回 0。

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
| **rejectRate 拒单率（2026-10-19 扩展）** | 周期内创建且 `order_stage='CANCELED'` 且 `cancel_reason='MERCHANT_REJECT_ORDER'`（45号商家拒单不可变事实）的订单数 / paidOrderCount，百分数 1 位小数；分母 0 → null。迟到支付超时/运营异常关闭等其他 cancel_reason 不计入 |
| newCustomers 新增客户 | 周期内创建首个本店订单的去重客户数（MIN(created_at) 落在周期内的 user_id 数） |
| repeatRate 复购率 | 周期内下单 ≥2 次客户数 / 周期内下单客户数，百分数 1 位小数；分母 0 → null |
| reviewAvg 评价均分 | 周期内新增、score_included=1 的 review 的 composite_score 均值（PRD：仅统计有效完成订单评价），0.0-5.0 保留 1 位小数字符串；周期内无评价 → null；评价内核未装配 → null |
| **scheduleUtilization 排期利用率（2026-10-19 扩展）** | SCHEDULE 域 53号读侧聚合：availableMinutes = Σ OPEN `schedule_availability_window` 与窗口 [from,to) 的重叠分钟 × configured_capacity；bookedMinutes = Σ CONFIRMED `schedule_reservation` 与窗口的重叠分钟；utilizationRate = booked/available（百分数 1 位小数；分母 0 → null "--"）。分钟粒度精确（SSOT §13 分钟级对齐）。独立端点，见 §3 |
| **服务分析下钻（2026-10-19 扩展）** | Top5 中的服务可进入该服务周期内订单明细分页（只读，本店双键，创建时间倒序+id 稳定次序）；该服务在窗口内无订单 → 404 同形防枚举；行仅含订单自身事实（orderNo/stage/金额/时间），无客户字段 |
| ~~拒单率 / 排期利用率 / 服务分析下钻~~ / 客户消费分层 | ~~V1 不做~~ → 前三项 2026-10-19 扩展已落地；**客户消费分层仍不做**（CCR 登记待裁，见 §6） |

比率字段为字符串（如 `"83.3"`，无 % 号）；null 表示不可计算（页面显示 --）。环比 delta 百分数 =（本期−上期）/上期×100 保留 1 位，上期为 0 或指标为 null → null。

## 3. HTTP（10号 §4.9 同步）

MINIAPP Bearer + OWNER 坐标复验（同 10号 §4.1/69号：`requireOwnerRead` 于门店 guard 事务内，ACTIVE/OFFLINE/FROZEN 存量读取家族；越权/不存在 403 同形防枚举）；`Cache-Control: no-store`；只读无 X-Request-Id；400 坐标/range/from/to/limit/分页非法；401/403/503 同 69号；无新增错误码。

| 方法/路径 | 请求 | data |
|---|---|---|
| GET `/merchant/stats/overview` | merchantId、storeId 必填；range? 枚举（默认 LAST_7D）；range=CUSTOM 时 from/to 必填 | `{range, from?, to?, revenue, orderCount, newCustomers, avgTicket, verifiedOrderCount, verifyRate, refundOrderCount, refundRate, repeatRate, reviewAvg, rejectRate, deltas:{同键环比百分数或 null}, generatedAt}` |
| GET `/merchant/stats/services/top` | merchantId、storeId；range?；from/to?（CUSTOM）；limit? 1..5 默认 5 | `{range, from?, to?, items:[{serviceId, serviceName, orderCount, revenue}]}`（周期内创建订单按 service_id 聚合，serviceName 取该服务最新快照名；按 orderCount 降序、serviceId 升序） |
| GET `/merchant/stats/revenue-trend` | merchantId、storeId；range?；from/to?（CUSTOM） | `{range, from?, to?, buckets:[{date:"YYYY-MM-DD", revenue, orderCount}]}`（按日闭区间；TODAY 为单日桶；CUSTOM 最多 92 桶） |
| GET `/merchant/stats/services/{serviceId}/orders`（2026-10-19 扩展） | merchantId、storeId；range?；from/to?（CUSTOM）；page/pageSize（1..100 默认 20） | `{serviceId, serviceName, range, from?, to?, page, pageSize, total, items:[{orderId, orderNo, displayStatus, payAmount, refundedAmount, createdAt, appointmentStartAt, verifiedAt}]}`（该服务在窗口内的本店订单明细，创建时间倒序+id 稳定次序；displayStatus 为 order 域统一 DisplayOrderStatus 十值投影——技术基线"由 order 统一计算"/ARCH-005，裸 stage 不过面；窗口内无该服务订单 → 404 防枚举） |
| GET `/merchant/stats/schedule-utilization`（2026-10-19 扩展） | merchantId、storeId；range?；from/to?（CUSTOM） | `{range, from, to, availableMinutes, bookedMinutes, utilizationRate}`（SCH 读侧聚合；端口未装配时三值为 null "--"） |

generatedAt 为毫秒精度带偏移 ISO 字符串（服务端聚合完成时刻）。

## 4. 架构归属

ORDER 域拥有统计投影（聚合 SQL 在 pet-order-biz 自有 Mapper XML，走 `idx_order_store_stage_created` 等既有索引，禁全表扫；92 天窗护栏即性能护栏）；评价均分经 review 域新增只读面 `ReviewQueryApi.storeScoreSummary`（review-biz 自有 XML，`idx_review_store_score` 既有索引），boot 侧以可选端口注入（评价内核未装配 → reviewAvg=null）；**排期利用率经 schedule-api 新只读端口 `ScheduleUtilizationQueryApi`（2026-10-19 扩展）：实现为 schedule-biz 自有 `ScheduleUtilizationMapper.xml`（仅触 SCH 自有 `schedule_availability_window` / `schedule_reservation`，store 前导索引），order-biz 经既有 api 依赖消费，boot 侧在 stats 装配内以 SCH 实现注入（SCH 内核随 order.merchant 链装配）；端口归约：端口本身不做 OWNER 验证（纯事实），消费方在 guard+OWNER 复验后调用**；OWNER 复验经既有 `MerchantOrderAuthorityApi`。无跨模块持久层访问（ARCH-002）、biz 不依赖 biz。

## 5. 验收要点

- 金额/比率断言：两位小数金额字符串、1 位小数比率字符串、null 语义（分母 0/无评价/上期为 0/端口未装配）。
- 周期断言：LAST_7D=近 7 个 UTC 日（含今日）减 7 天锚点起；环比窗口等长紧邻；CUSTOM 闭区间对齐同款 UTC 日桶、前窗等长紧邻。
- 护栏断言：CUSTOM >92 天/倒序/格式非法 400。
- 拒单率断言：仅 `MERCHANT_REJECT_ORDER` 取消计入分子；迟到支付超时/运营关闭不计入；分母为已支付订单数。
- 下钻断言：分页稳定（创建时间倒序+id）；窗口外/无订单服务 404 同形；行无客户字段。
- 排期利用率断言：OPEN 窗口重叠产能×容量、CONFIRMED 预约重叠分钟；端口未装配全 null；装配后读失败 503。
- 越权断言：非本店坐标 403 同形；未知 range/limit 400。
- 性能：聚合语句必须命中 store 前导索引；测试以 EXPLAIN 断言非全表扫（或以覆盖索引扫描计划断言）；92 天上限即大窗护栏。

## 6. CCR 登记待裁（2026-10-19 二次扩展后余量）

2026-10-19 用户裁决"继续扩展CCR"已落地本契约原登记项：

- ~~1. 拒单率~~ → **已落地**（`cancel_reason='MERCHANT_REJECT_ORDER'` 订单域事实聚合，40号列既已存在，无需新事实源）；
- ~~2. 排期利用率~~ → **已落地**（53号排期读侧聚合口径见 §2，schedule-api 新只读端口）；
- ~~3. 服务分析下钻明细列表、自定义时间区间~~ → **已落地**（下钻只读分页 + CUSTOM ≤92 天窗）。

仍未裁/不做（维持登记）：

1. 客户消费分层（PRD 5.14"客户消费分层"区块——消费额分层统计口径未裁，涉及 69号派生面扩展）；
2. T+1 报表仓库口径与首页看板 hero 数据区回补（工作台数据区渲染属工作台切片）；
3. 排期利用率的分服务/分人员下钻（当前为单店单值；分维度需 53号读侧扩展口径）。
