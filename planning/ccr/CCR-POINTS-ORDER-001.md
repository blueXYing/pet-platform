# CCR-POINTS-ORDER-001：订单奖励绑单发放 + 退款扣回（PTS-001/PTS-002）契约提案

状态：**RESOLVED（已收口，2026-10-16 首付事实 O(1) 查询切片）**。本 CCR 由 60 号契约 §7
（2026-10-15 增补，PR #170）首次登记：PTS-001 订单奖励绑单发放 + PTS-002 退款扣回，裁决链
为 32 号裁决第二节 + CCR-POINTS-TASK-001 细则定稿；当时的实现取舍遗留一个登记在案的结构
缺口（本文件 §二）。本切片（分支 `codex/pts-firstpaid-o1-20261016`）收口该缺口，产品规则
零变化（SSOT > 最终 PRD > 技术基线优先级不变）。

## 一页结论

PTS-001/PTS-002 交付时（PR #170，已合），首笔支付**订单标识**的解析落在 boot 适配器：经
`OrderQueryApi.listMyOrders` 同主体全量分页扫描过滤 PAID，O(全量订单)，并设 5000 单防御
上限（50 页 × 100），超出 fail-closed 503。该取舍在 60 号 §7.1 披露并登记为缺口。本切片
将其收口为 order 域自有 O(1) 事实查询：`OrderFirstPaidFactsApi.firstPaidOrder`（07 号
§7.1.1 增补登记），boot 适配器只搬运事实（ARCH-005：读侧投影由 order 域提供）。无新
HTTP 路由（11 号 operations 计数不变）、无新错误码、无 Schema/Event 变化。

## 事实基础（收口时核实）

- PR #170 现状：`PointsTaskConfiguration.BootPointsTaskFacts.firstPaidOrder` 分页扫描
  `listMyOrders`（PAID + paid_at 非空过滤，最早 paid_at、最小规范 orderId 决胜），
  `total` 超过 5000 抛 COMMON_DEPENDENCY_UNAVAILABLE。
- 契约事实：07 号 §7.1 已有 `firstPaidAt`（MIN(paid_at)，2026-10-13 增补）；首单标识
  无契约面。60 号 §7.1 事实口径引用 boot 分页实现。
- 架构事实：ARCH-002（禁跨模块读持久层）+ ARCH-005（读侧投影归 order 域）；生产 SQL
  只进本模块 Mapper XML（22 号裁决，check-persistence-style 门禁）。
- 零新 HTTP 路由纪律：11 号 operations 计数钉死（65 号不许占）。

## 收口裁决（本切片，逐条披露）

- **O1 事实端口**：pet-order-api 新增独立只读事实端口
  `OrderFirstPaidFactsApi.firstPaidOrder(UserOrderFactQuery) -> OrderFirstPaidFact(orderId,
  paidAt)`；pet-order-biz `OrderFirstPaidFactsApiImpl` + `OrderQueryMapper.xml` 单条索引行
  读实现（`payment_status='PAID' AND paid_at IS NOT NULL`，`ORDER BY paid_at ASC, id ASC
  LIMIT 1`）。07 号 §7.1.1 为契约登记。
- **O2 口径等价**：付费集与决胜口径与旧分页扫描完全一致（同一 PAID 过滤——含迟到支付
  保持关闭但标记 PAID 的订单；最早 paid_at 取胜；同一时刻最小规范 id 决胜），行为等价性由
  测试保证（order-biz MySQL 边界测试 + boot PointsTaskHttpTest 全栈 FIRST_ORDER 绑单断言）。
- **O3 上限取舍**：5000 单防御上限属旧分页实现（防无界扫描），非产品规则；新端口为单条
  索引行读，无无界扫描面，上限随实现一并移除。任何订单量下不再 fail-closed，均返回确定性
  真值。PR 描述披露。
- **O4 装配**：端口为无会话主体的纯事实读，与 `UserQueryApiImpl` 同一装配先例，由 boot
  积分适配器以内联构造携带；`firstPaidAt` 仍走 `ObjectProvider<OrderQueryApi>` 的既有
  fail-closed 装配，内核对订单读侧未装配的保守行为不变。
- **O5 边界**：零新 HTTP 路由；只读面无幂等要求；biz 不依赖 biz（api→biz 实现方向）；
  不跨模块访问 Repository/Mapper/DO/Entity；Snowflake BIGINT / HTTP ID String 纪律照旧
  （orderId 出参为规范十进制字符串）。不动 admin/governance 文件，不动 pet-refund。

## 状态

- PTS-001/PTS-002 主体（PR #170）：已交付。
- 首单标识 O(1) 事实查询收口（本切片）：已交付，60 号 §7.4、07 号 §7.1.1 同步登记。
- 本 CCR 无未决项。
