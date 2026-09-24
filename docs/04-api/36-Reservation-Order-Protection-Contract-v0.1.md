# SCH-003 / ORDER 预约与人员保护联合契约 v0.1

状态：**ACCEPTED_CONTRACT_NOT_IMPLEMENTED**，2026-09-24。批准来源：[ROC-1～6 六项回执](../../planning/ccr/CCR-W2-API-001/reservation-order-protection-decisions.md)；业务裁决进入 [SSOT §31](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)。本文件冻结逻辑合同，不宣称当前 06 Schema、07 内部 API、10 HTTP、11 OpenAPI 或后端已经同步；没有 DDL、真实迁移、通过的并发测试或生产启用。适用已批 [34 号共同 guard/双 claim/旧窗隔离](34-Schedule-Protection-Contract-v0.1.md)；34 号尚未实现部分继续保持关闭。

## 1. 不变量和事实所有权

- 单次到店/接送都必须预约，商家自定义分钟级半开区间 `[start,end)`，不设全平台固定 60 分钟槽；接送 `returnStart >= pickupStart + 120 分钟`。到店实际预约区间继续由既有服务时长和校验规则确定，本合同只增加原 GENERAL 窗关联，不重算或改变区间。
- SCH 是窗口、排班、具体服务能力、预约和 claim 的事实 Owner；ORDER 是 `pet_order.service_staff_id` 与 `order_staff_assignment.is_current` 当前最终指派的唯一 Owner；MER 是员工归属、在职/在岗与版本事实 Owner。跨域仅经各自 `api`，不跨模块访问 Repository/Mapper/DO/Entity。
- 每次增加/改变有效预约，或减少服务能力、排班、员工资格、原窗容量的写，都参加已批每店稳定 guard。幂等 Admission/执行锁和授权重验按 [23 号](23-公共接口与幂等契约补充-v0.1.md)及 34 号；随后按数值 storeId 顺序拿 SCH guard，再作同一主库 DataSource、同一顶层本地事务的权威**当前读**和写入，持锁至提交/回滚。独立只读 `REQUIRES_NEW`、RR 旧快照、缓存或远程事实不能替代锁内当前读。
- `TEMP_LOCKED/CONFIRMED` 是有效占用；`lock_expire_at` 已过而 EXPIRED 尚未提交仍保护。只有已提交 `RELEASED/EXPIRED` 才解除 claim 占用。退款申请、退款单创建、退款处理中不提前释放；正式退款仅在退款最终成功的既有路径释放。旧接送 GENERAL 的盘点/隔离继续按 34 号，不借本合同复制为两个方向窗。

## 2. ROC-1：跨服务完整人员与配置容量证明

写入候选加入后，以本次受影响的时段为起点，纳入同店各服务所有相交有效预约；对接送预约把另一条方向 claim 及由它相交的预约递归纳入，形成完整时间相交闭包。不能按单个 service/window 各自算人数，也不能漏掉跨服务复用的员工。相关预约资料至少包含 `reservationId,orderId,storeId,serviceId,fulfillmentType,status,claims[],currentStaffId?`；一条 GENERAL claim 使用实际到店区间，两条 PICKUP/RETURN claim 各用原所选完整窗区间。原窗口身份、时间、归属与 claim 基数不符即不可证明。纯历史且与目标无时间关联的订单仍核验完整性和原窗保护，不用员工当前资格倒算已过去的履约。

每笔相关预约恰由**一位**候选员工覆盖所有 claim 段。候选须为 MER 同店 `ACTIVE` 且在岗、SCH 对具体 service 有 ENABLED 能力，并由相邻可接续的 AVAILABLE 排班无空档覆盖**每个**所需完整区间；接送两段之间不需持续排班。同一笔接送两段若相交，员工占用取两段并集一次，不新增“方向窗不得重叠”的产品门槛。两笔不同预约若时间并集相交，不能使用同一员工；半开区间边界相接可复用。ORDER 已有商家当前指派时该员工固定，不能在证明中改派；无最终指派时仅作内部数学匹配，求解结果不写 `pet_order.service_staff_id`、不形成 `assigned_by=SYSTEM`，不向用户展示。

每个原窗口另以所有有效 claim 的端点切分半开原子区间，逐段证明重叠占用数 `<= configured_capacity`。接送 claim 占完整所选窗；GENERAL claim 占按服务时长确定的实际预约区间。配置容量和跨服务员工匹配两个条件**同时**成立才可提交。窗口 close、降容量或移时间遇旧窗有效占用，仍按已批 §29/34 直接拦截，不能靠人员重新匹配绕过。

实现须采用完整可行性搜索：先验证全量结构、ORDER/MER/SCH 当前事实及已固定指派，再检查每段配置容量；对未指派预约按候选数升序做穷尽回溯，满足所有约束找到完整解才接受。确定性排序、位图缓存和安全的连通分量拆分可优化，但贪心、每分钟独立匹配、逐服务人数减占用都不是完整证明。没有批准按预约数、员工能力数设置新的业务上限。运算可设技术时间预算；预算耗尽、事实不全、未知枚举或旧接送 GENERAL 无法恢复时返回 `503 COMMON_DEPENDENCY_UNAVAILABLE` 并整笔回滚，不当 `409` 容量已满。事实可信且完整搜索无解或配置容量不足时返回 `409 SCHEDULE_CAPACITY_EXCEEDED`；已指派员工被已知减员目标破坏时返回 `409 COMMON_CONFLICT`。批量关窗不能将 503 伪装为普通 `blockedWindows`。

反例：甲同时有服务 A/B 能力，A 10:00–11:00 已锁，B 同时段即使“B 合格人数 1、B 占用 0”也不能再锁；若乙也合格可排班，搜索可把一笔内部匹配给乙。X 可由甲/乙、Y 仅甲且相交时，完整搜索必须能给 X 选乙、Y 选甲，不得因先贪心选甲而误拒。

## 3. ROC-2：接送同一最终人员覆盖两完整窗

接送仍按 34 号分别选择一个 OPEN PICKUP 和一个 OPEN RETURN 原窗；两个 `selectedPickupWindowId/selectedReturnWindowId` 与开始值、店/服务/kind 在 guard 内复核。两条 claim 及父预约同事务变化，各 claim 保存原 `windowId,kind,startAt,endAt`，按**完整** `[window.startAt,window.endAt)` 占用；返程开始服务端仍强校验 `>= pickupStart+120 分钟`。不能从单一预约主区间、固定 60 分钟或 service duration 推两个方向结束值。

一笔接送订单只允许一名 ORDER 当前最终服务人员，该人须分别完整覆盖 PICKUP 和 RETURN，两个方向之间可无班。甲只覆盖上门、乙只覆盖送回时没有可行同人，hold/指派失败；另有丙覆盖两段且不与其他预约冲突才可能成功。不能把两名内部技术候选写成两个商家指派或在 C 端提供选人。

## 4. ROC-3：到店单 GENERAL 原窗

已批准的新增产品选择：**一笔新到店预约须由一条同店同服务 OPEN GENERAL 原窗完整容纳实际 `[appointmentStart,appointmentEnd)`**，只生成一条带原 `windowId` 和实际区间的 GENERAL claim；不能跨相邻 GENERAL 窗拼接。旧请求只有 `appointmentStart/appointmentEnd` 时，仅可在 guard 内**唯一一条**原窗完整包容区间且无歧义时关联；不得按开始点命中、取第一条、默认造窗或借选窗逻辑改写服务时长决定的结束时间。后续 C 可约每项需提供原 `windowId/kind`，新创建/改期建议带 `selectedGeneralWindowId` 十进制 String，服务端仍复查原窗归属、OPEN、区间包含、服务时长与容量。该字段和当前路由尚未在 07/10/11 实现，不把六字段旧响应冒充选窗 ID 来源。

跨窗、无唯一完整包容窗或历史重叠窗歧义的新单失败关闭，提示重选；这项限制来自本轮批准，不倒称旧 PRD 已禁止。存量跨窗订单继续原履约并保留证据，不硬关联其中一窗、不自动取消、不当空位；迁移必须逐单盘点、可证明才回填，异常门店/服务隔离相关发布和减员。若未来要允许跨窗，须另行批准选窗数量、多 GENERAL claim 基数及逐窗容量扣减的合同。新事实的 claim 基数为 IN_STORE 恰好一条 GENERAL，PICKUP_DELIVERY 恰好一条 PICKUP 加一条 RETURN；缺段或多段都不提交。

## 5. ROC-4：hold 先于主单创建但 order_id 非空

06 号 `schedule_reservation.order_id NOT NULL` 继续有效。ORDER 接收已绑定原 `X-Request-Id` 的创建意图后，先预分配 Snowflake `orderId`（如需 `orderNo` 亦先分配），仅分配 ID、不写不完整 `pet_order`。同一顶层主库本地事务按已批顺序：锁幂等执行记录并重验当前身份/动作 → SCH guard → 锁内复核服务、原窗、claim、人员和 ROC-1 → SCH `hold(orderId,...)` 持久化非空 `order_id`、预约与 claim → ORDER `create(orderId,reservationId,...)` 写主单及快照 → 校验 `reservation.order_id=order.id` 且 `order.reservation_id=reservation.id`、店/服务/用户一致 → 审计、首次成功回执一起 commit。hold 到 create 之间的 `PENDING_BIND` 只存在本事务；**提交后**若找不到双向主单绑定为损坏事实，503 告警，不能解释为普通未指派。

创建主单或同事务参与方失败，所有预约/claim/主单/成功回执一起回滚。提交 ACK 未知时用原 requestId 查原尝试，不能换键重试而重复锁位；同 key 异参仍是 `409 IDEMPOTENCY_KEY_CONFLICT`。外部支付/Provider 网络调用只能在该事务提交之后；提交后支付创建失败按既有幂等关闭/释放与权益补偿链处理，不谎称主单未创建。此本地同库合同不批准跨服务分布式全局事务，也不改变支付超时、迟到支付自动原路全额退款与优惠券原规则。TX/优惠券/支付的分阶段受理及回执仍按各 Owner 与 23 号正式同步。

## 6. ROC-5：ORDER 当前指派完整事实与生命周期

ORDER 以 `pet_order.service_staff_id` 和 `order_staff_assignment.is_current=1` 的**一致组合**为唯一当前最终指派；两者须同空或指向同一员工，历史 assignment 行不参与当前容量。ORDER 在同 guard、同主库事务提供两类公共事实：

| 逻辑能力（Java 方法名待后续同步） | 必要回执与完整性 |
|---|---|
| 按 `storeId,reservationIds` 查询 | 对每个输入 ID 恰有一项；区分仅当前事务短暂未绑定主单、已绑定未指派、已指派、不一致。含 `orderId?,currentStaffId?,orderStage,verificationStatus,orderVersion,assignmentVersion?,protectRequired`。提交后未绑定不得成功返回。 |
| 按 `storeId,affectedStaffIds?` 查询 | 先枚举该店**全部当前指派**，核验归属、当前唯一性、双向 reservation 绑定及两份当前人员一致，再给目标员工子集；含 `complete=true,totalCurrentCount` 和每项 `orderId,reservationId,storeId,serviceId,currentStaffId,orderStage,verificationStatus,orderVersion,assignmentVersion,protectRequired`。空列表也须由成功的全量查询证明。不能只沿有效 claim 的 reservationIds 反查。 |

REFUND 状态如需参与一致性判定，只能经 REFUND 公共 API 取正式 `RefundStatus=CREATED/PROCESSING/SUCCESS/FAILED/UNKNOWN`；无退款单与读取失败/未知值必须区分。ORDER 底层 `OrderStage=PENDING_PAYMENT/PENDING_CONFIRM/PENDING_SERVICE/COMPLETED/CANCELED`、`VerificationStatus=UNVERIFIED/VERIFIED`，SCH 预约 `TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED`，均沿[技术基线 §4](../02-architecture/05-技术基线-v0.6.md)。展示 `DisplayOrderStatus`、退款申请或售后标签不能独自证明人员可释放；`refund_order` 创建后禁止核销的 ORDER 业务守卫保持。

| 已提交底层事实组合 | 保护解释 |
|---|---|
| `PENDING_PAYMENT`、TEMP_LOCKED、无当前指派 | 预约仍入内部可行性匹配；`protectRequired=false` 仅因尚无最终指派。本合同不新增待支付禁止指派门禁。 |
| `PENDING_PAYMENT/PENDING_CONFIRM/PENDING_SERVICE`、`UNVERIFIED`、预约 TEMP_LOCKED/CONFIRMED | 若有当前指派，则 `protectRequired=true` 且固定该员工；无指派继续内部匹配。是否可在待支付指派由 ORDER 动作权限另管，不因本表自动开放。退款申请、退款单创建或处理中不释放 claim。 |
| `CANCELED` 但预约仍 TEMP_LOCKED/CONFIRMED | 不论取消/退款显示，预约仍占用；现存当前指派保守地 `protectRequired=true`，至 SCH 提交 RELEASED/EXPIRED。 |
| `COMPLETED` 且 `VERIFIED` | 当前人员可保留作历史与评价，不再占未来人员资源；若仍存在未来区间有效 claim，503 对账。历史原窗保护按 SCH 状态。 |
| `CANCELED` 且预约 RELEASED/EXPIRED | 当前人员可留历史，`protectRequired=false`；取消/退款与释放不一致另对账，不恢复原订单。 |
| `PENDING_CONFIRM/PENDING_SERVICE` 且 `UNVERIFIED` 却预约 RELEASED/EXPIRED；`COMPLETED` 且 `UNVERIFIED`；双向关联或两份当前指派不一致；未知状态/缺必要 claim | `INCONSISTENT`，503 并阻断受影响容量写，不能把它当零指派。`CANCELED` 且 `VERIFIED` 可能是已核销后退款，不能仅凭这两个字段判异常。 |

按门店/员工查询须能找出**预约已 RELEASED 但 ORDER 尚有在途当前指派**，再据表判断是需保护还是损坏事实；不能因 claim 清单为空直接放行减员。分页仅在同一 guard/事务当前读下完成且校验总数覆盖；物理索引/EXPLAIN 由 ORDER 实施切片交付，本文件不提供可执行 DDL。SCH 不直接读 ORDER/MER 表。MER 停用旧 `IMPLEMENTATION_BLOCKED` 保留，直到完整公共事实和真实并发证明交付。

商家指派/改派、ORDER 状态转移、SCH 释放与 MER 降资格均在同店 guard 下按固定顺序执行；指派前校验员工归属、在职在岗、具体服务能力和所有 claim 时段，再将目标人作为固定值重跑 ROC-1。成功才在同事务更新 `pet_order.service_staff_id`、旧/新 `order_staff_assignment.is_current`、版本、审计及首次幂等回执。减少已指派人的可用性，即使总人数仍够，也要先合法改派，不得直接置空未完成订单。ORDER/MER 不得先锁本域行后等 SCH guard。未知事实、读取失败、查询不完整或求解超预算均 503 并回滚；已知业务阻挡 409。系统异步转移/释放各自用事件/任务幂等，不能靠事件到达时间提前消除占用。

## 7. ROC-6：改期保留当前指派，失败保留旧事实

改期仍每单最多一次，仅预约开始前、同商家同服务，先锁新时段成功后才释放旧时段，成功后重新待确认并重算 30 分钟。已有 ORDER 当前商家指派时，交换将该员工固定到**新** GENERAL 或两条完整接送 claim 做 ROC-1 证明；他不能完整覆盖或新容量不够，则 `409 SCHEDULE_SWAP_FAILED`，原预约、原 claim、当前指派、改期次数与确认周期保持不变。不能先写新窗、自动清空当前人或暗中改派到技术匹配的另一人。尚未指派的订单按内部完整求解保护，但不写自动人员。此行为是本轮明确批准的新产品选择，不倒称旧 PRD 已规定；退款、核销及已支付快照其他规则不变。

## 8. 交付与失败关闭

36 号只批准 ROC-1～6 的逻辑合同。后续唯一 Writer 须按 06/07/10/11/12 分别同步物理 Schema、内部/HTTP DTO、`windowId/kind` 与选窗字段、错误映射及 OpenAPI `x-contract-status`，再实现真实 MySQL 共同锁、全量查询、搜索与迁移；当前 `GET /api/v1/c/services/{serviceId}/availability` 六字段不能被称为已能提供选窗 ID 或接送组合保证。可约 GET 只作展示，hold 锁内复核是最终授权。没有完整合同/事实的相关减员、接送 hold 与歧义到店新单继续按已批失败关闭。

[SCH-003 测试映射](../../planning/issues/wave-3/SCH-003-contract/REVIEW-TEST-MAP.md) P01～P24 均 **NOT_EXECUTED**；需真实 MySQL 多连接顺序、幂等/回滚、存量 GENERAL、完整性和性能预算证据，不能以 mock/单窗口单服务测试代替。已批逻辑不授权 PR 合并、生产迁移或发布；未批准统一固定 60 分钟槽、跨店改期、自动人员展示、新业务上限或 V1 范围外能力。
