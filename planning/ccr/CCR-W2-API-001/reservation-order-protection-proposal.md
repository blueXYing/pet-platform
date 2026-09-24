# SCH-003 / ORDER 预约、人员与指派保护联合 CCR v0.1

状态：**ACCEPTED_CONTRACT_NOT_IMPLEMENTED**，2026-09-24。用户已[批准 ROC-1～6 六项推荐](reservation-order-protection-decisions.md)，规范正文见[36 号契约](../../../docs/04-api/36-Reservation-Order-Protection-Contract-v0.1.md)及 SSOT §31。本文保留审阅论证；若仍有“候选/待批准”的历史措辞，以批准回执和 36 号为准。06/07/10/11/12 号当前接口尚未同步。四项 SCHC-1～4 已在[回执](schedule-write-completion-decisions.md)批准，不在本轮重问。本文不含 DDL、迁移、Java 实现、生产开关或测试通过声明。

## 1. 权威依据与缺口性质

| 已定规则，本文不得更改 | 来源与实现含义 |
|---|---|
| 单次服务均须预约；分钟级自定义区间，无统一 60 分钟槽；接送返程开始 ≥ 上门开始 +120 分钟；每单最多改期一次，先锁新时间再释放旧时间 | [SSOT §3/§12/§13](../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[最终 C PRD](../../../docs/01-prd/02-PRD-C端用户-V1.0-最终基线.docx) §5.1.15/§5.1.19、[商家 PRD](../../../docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx) §5.4。不得从 `duration_minutes` 推导接送两段窗长。 |
| 有效容量为 `min(配置容量, 当前合格可用人员数)`；员工须在职在岗、同店、有具体服务能力、排班无空档覆盖所需区间；用户不选人，商家指派 | SSOT §12/§13/§29；最终 C PRD §5.1.15 与字段“本次服务指派的员工”、商家 PRD §5.6。减少可用性要保护既有预约和已指派订单。 |
| 接送分别选一个 PICKUP、RETURN 完整窗，两条原窗 claim 同事务；同店 guard 串行容量写；旧接送 GENERAL 隔离 | 已批 SCHC-1/3/4。到店 GENERAL 的窗口关联仍缺合同。 |
| 临时锁定 10 分钟；TEMP_LOCKED/CONFIRMED 在状态真正变为 EXPIRED/RELEASED 前计保护；退款申请、退款单创建、处理中不提前释放，退款最终成功才释放 | 最终 C/商家 PRD、[07 §6.2](../../../docs/04-api/07-内部API-Contract-v0.6.md)、34 号。过期时间已到但未提交释放，仍计占用。 |

现有 [07 §6.1.1](../../../docs/04-api/07-内部API-Contract-v0.6.md) 和 [10 §3.4](../../../docs/04-api/10-HTTP-API-Contract-v0.4.md) 的查询按单服务窗口列出合格人员数及重叠预约数；[06 Schema](../../../docs/03-database/06-核心数据库Schema-v0.1.sql) 的 `schedule_reservation.order_id NOT NULL`，而 07 §19.1/10 §3.5 先调用 `hold` 再创建 `pet_order`。07 的 `ScheduleCommandApi.assignStaff` 只是旧草案签名，最终指派的权威列在 ORDER 的 `pet_order.service_staff_id` 与 `order_staff_assignment`。不能把每服务独立人数、单区间重叠数、Redis 锁或这个旧方法当作以下缺口已解决。

**本轮新增并已获产品批准的三项**：接送订单由同一名最终指派人员承担两个完整方向窗；到店新单由一条 GENERAL 原窗完整包容，跨窗新单失败关闭；改期后原指派不覆盖新时间则拒绝并保留旧事实。对应 ROC-2、ROC-3、ROC-6；它们不是旧 PRD 已明示的规则。**已获批准的技术合同**为 ROC-1、ROC-4、ROC-5；物理索引、迁移和事务测试仍未实施。

## 2. 已批准的六项联合决定

| 编号 | 已批准的逻辑契约 | 决策性质 |
|---|---|---|
| ROC-1 | 每次会减少可用性或增加/改变预约占用的事务，在已批同店 guard 下，对**同店所有相关服务的有效预约**做完整人员可行性证明：每笔预约由一位合格员工覆盖它的所有占用段；已由商家指派的员工固定，未指派预约只参加内部数学匹配。窗口配置容量另按每段时间证明。找不到解则 409，事实不完整或求解超预算则 503 并回滚。 | 技术方案；落实 SSOT 容量和保护规则。 |
| ROC-2 | 一笔接送订单仍只有一个 ORDER 当前服务人员；该人员须分别覆盖其 PICKUP 与 RETURN 两条完整 claim，中间空档不要求持续排班；不能为两个方向暗建两名“最终指派”。两个 claim 可分别与其他订单发生冲突，按整段半开区间判定。 | 已批准的新增产品语义。 |
| ROC-3 | 新到店预约须由**一条** OPEN、同店同服务 GENERAL 窗完整包容 `[appointmentStart,appointmentEnd)`；GENERAL claim 保存原 `windowId` 和实际预约区间。现有请求可在 guard 内仅当唯一包容窗时关联；后续 C 可约结果与下单/改期增 `selectedGeneralWindowId`，服务端仍校验区间和原窗。跨窗或歧义的新预约失败关闭，存量跨窗不伪造单 claim，逐单迁移/处置。 | 已批准的新增单窗产品选择；ID 与唯一关联是技术合同。不改变既有到店服务时长决定预约区间的规则。 |
| ROC-4 | ORDER 先预生成 `orderId`，同一顶层主库本地事务中依次执行 SCH hold（持久化非空 `order_id`）与 ORDER 创建同 ID 订单；提交前不可对外返回成功或调用外部支付。失败全事务回滚，不留已提交“无订单 hold”。 | 技术方案；需与 TX/优惠券/支付 Owner 同步。 |
| ROC-5 | ORDER 提供按 reservation 的当前指派事实和按 store/staff 的**全量当前指派完整性事实**；同 guard、同事务、主库当前读。指派/改派、ORDER 状态转移、MER 停用均参加共同锁；无活跃 claim 却仍应服务的订单视不变量损坏，阻断减员并对账。 | 技术方案；保护状态真值表见 §6。 |
| ROC-6 | 已有最终指派的订单改期时保留该指派；若原员工无法覆盖新时间，则改期失败、原预约与指派保持不变，不自动撤派。 | 已批准的新增产品选择；旧规则未明确这一分支。 |

ROC-2 的同人约束只约束商家最终指派，**不在 C 端展示或自动生成商家指派**。ROC-1 的内部匹配是可行性证明，可在下一次事务重算；没有 `assigned_by=SYSTEM`、没有订单 `service_staff_id` 写入，也不形成客户选人。若未来产品明确允许接宠和送回由不同员工执行，就必须先改变“一单一名最终人员”的 ORDER 数据契约、商家页面与评价归属，另走 CCR。

## 3. ROC-1：全店完整容量证明

### 3.1 输入与不变量

一个有效预约单元 `u` 含 `reservationId,orderId,storeId,serviceId,fulfillmentType,status,claims[],currentStaffId?`。到店 `claims` 为一条 GENERAL，时间为实际 `[appointmentStart,appointmentEnd)`；接送为已批的两条完整所选窗 PICKUP/RETURN，时间从原窗快照取。`TEMP_LOCKED/CONFIRMED` 均是权威占用，过期时间本身不移出。容量/人员求解至少覆盖本次新增或变化区间的全部相交预约，并把同一接送预约的另一个方向及其相交预约递归纳入；可实现为全店未来相交连通分量，不能按服务裁剪。纯历史且与目标无时间关联的订单仍接受完整性核验和原窗保护，不要求用员工**当前**资格倒算历史履约。旧未恢复接送 GENERAL 或缺 claim 的相关单元标记 `UNPROVABLE`，不合成零占用。

对每笔预约，候选员工集合必须同时满足 MER 同店、ACTIVE、在岗，SCH 具体服务能力 ENABLED，且 AVAILABLE 排班（相邻段可接续）**无空档覆盖该笔的每个 claim 区间**。接送两个区间之间可以无班；同一订单两段重叠时员工时间占用取其并集一次，不额外添加“返程窗不得与上门窗相交”的产品限制。最终 ORDER 当前人员存在时，候选集合收窄为该人；不存在时匹配仅是内部证明。任意两笔**不同预约**给同一员工的占用并集不得相交；相邻半开边界可衔接。由此避免一个兼容两项服务的员工被两笔重叠预约重复使用。

另对每个 OPEN 原窗口做独立配置容量检验：把该窗内所有有效 claim 的起止点排序，逐个半开原子区间计算重叠 claim 数，均须 `≤ configured_capacity`。接送 claim 占满所选完整窗；到店 claim 用实际服务区间。员工匹配与配置容量**两个条件都必须满足**，不能用 `min` 后的独立单窗计数替代整体证明。窗口关闭/降容量/移时仍先按已批 §29 拦截受占用原窗，不因求解可重排就放行。

### 3.2 建议求解器与失败模式

在 guard 后取本店全部有效预约、claim、原窗、员工当前事实与 ORDER 完整事实的同事务当前读，先验证全量结构与完整性，再取本次写入影响的时间相交闭包求解。先检查固定指派：相关预约是否有期望 claim 数、ID/区间/归属是否一致，固定员工是否在受影响时段合格、与另一固定指派是否重叠；再检查配置容量。其后对未指派预约按候选人数升序做**完整回溯搜索**，每步仅从满足所有区间且未与同员工已选预约相交的员工中取值；找到一个完整解即可接受。可对候选集合位图缓存和确定性排序优化，但拆分不得丢掉接送两段把两段连在同一预约上的约束。不能用贪心、按分钟各自匹配或逐服务分别匹配宣称成功。未批准预约数量上限；实现可以设置运算时间预算，**超时为依赖不可用 503，不当容量不足 409**，且不可写入任何部分匹配。候选内部匹配可不持久化，未来订单指派再整体重算。

`409 SCHEDULE_CAPACITY_EXCEEDED` 用于完整可信事实下求解无解或配置容量不足；已指派员工的资格/时段被已知变更破坏时，受保护写返回 `409 COMMON_CONFLICT` 并给适当业务提示。未知状态、ORDER 完整性缺失、读失败、陈旧缓存、求解超时或存在不能恢复的旧 GENERAL 则 `503 COMMON_DEPENDENCY_UNAVAILABLE`。不让批量关窗把 503 偷换为一条 `blockedWindows`。

**例 A（共享员工）**：店内只有员工甲同时会美容 A 和洗护 B。A 10:00–11:00 已 TEMP_LOCKED；B 同段配置容量 1，单看 B 的“合格人数 1、占用 0”会放行，完整匹配却无解，B hold 返回 409。若另有合格员工乙且可排班，求解可给 B 配乙，允许；任何技术匹配均不写 ORDER 最终指派。

**例 B（接送成对）**：甲只覆盖 PICKUP 10:00–11:00，乙只覆盖 RETURN 12:00–13:00，没有任何一人覆盖两段。按已批 ROC-2 同人约束，完整事实下返回 409。若另有丙分别完整覆盖两段且没有其他冲突，则本例可通过。不能以两个方向各有一人而通过。

**例 C（非贪心）**：旧未指派预约 X 可由甲/乙，新增 Y 仅甲，两段相交。先把 X 贪心放甲会误拒 Y；完整搜索把 X 放乙、Y 放甲可放行。没有商家改派发生。

## 4. ROC-3：到店 GENERAL 身份和跨窗

ROC-3 是**本轮新批准的产品选择**，不是对既有 PRD 的解释性重述。兼容期的到店命令仍携 `appointmentStart/appointmentEnd`；既有到店服务时长决定预约占用区间、服务端校验时长与实际区间的规则不变。SCH 在 guard 内读 OPEN GENERAL，要求仅一条同店同服务窗**完整覆盖已有规则算出的实际预约区间**，并保存该窗 ID 与原时间快照。只按开始点命中、取第一条、将相邻两个窗拼成一个逻辑窗均不合格；窗口关联步骤不得擅自重算或改写预约结束时间。即使新 HTTP 带 `selectedGeneralWindowId`，它只是防歧义的选择证据，服务端仍复查归属、状态、窗口包含及容量，不信任客户端时间。

建议的后续 HTTP/内部增量：GET 可约 `items[].windowId/kind`（34 号已要求）；IN_STORE 创建/改期可带 `selectedGeneralWindowId: String`，内部 `ReservationHoldCommand/RescheduleCommand` 同步该字段；PICKUP_DELIVERY 继续只带已批两个方向 ID。新客户端上线前，仅唯一包含可证明的旧请求可继续；歧义/跨窗请求不能因为缺字段默默创建。到店一般窗 claim 唯一键建议 `(reservation_id,kind)`，与接送同一基数约束，每个有效预约恰好 1 GENERAL 或 1 PICKUP+1 RETURN。

跨相邻 GENERAL 窗的新预约若本来可以按连续营业时间完成，本轮已批单窗规则会提示重选并暂时不能下单；这是**本轮新增的限制**，不能写成“既有 PRD 已禁止跨窗”。若产品未来要允许跨窗，应另定义用户选择几个窗、每段配置容量扣减、GENERAL claim 多条基数与服务时长区间，才可启用。存量跨窗订单维持履约，迁移时保留全部原始占用证据；不能硬关联其中一个窗或把它当空位。若选窗 ID 后仍出现同店同服务同 kind 的历史重叠窗，先隔离并修复数据，不靠 ID 掩盖重叠违约。

## 5. ROC-4：非空订单 ID 的原子绑定

`OrderCommandApi` 在接收已通过 Admission 的创建意图后，先由 ORDER 预分配 Snowflake `orderId`（如需 `orderNo` 同样预分配）；预分配只是 ID，不写一条不完整 `pet_order`。原 `X-Request-Id` 绑定整个创建参数，重放复用首次结果或查询原尝试；不得为补偿换新键重做。执行在**同一个顶层本地事务、同一主库 DataSource**中按 23/34 号顺序：锁幂等记录并重验身份 → SCH 同店 guard → 锁内复核服务/门店/人员/claim 与 ROC-1 → SCH `hold(orderId,...)` 插入 `schedule_reservation.order_id=orderId` 及 claim → ORDER `create(orderId,reservationId,...)` 插入主单与快照，校验双方 store/service/用户及唯一双向绑定 → 同事务记录审计和成功幂等回执 → commit。订单在 hold 和 create 之间仅对**本事务**暂未存在，允许内部 `PENDING_BIND` 短暂态；事务提交前必须验 `pet_order.id=reservation.order_id` 且 `pet_order.reservation_id=reservation.id`。外部事务不可见 `PENDING_BIND`，提交后发现它是损坏事实，返回 503 并告警，不当“未指派”。

外部渠道支付创建/拉起不得在持有 guard 或未提交事务里做网络调用。支付/优惠券的具体分阶段可靠受理仍由 TX/原 Owner 按 23 号与 07 §19.1 定稿；本 CCR 只冻结 `hold` 与主单的原子绑定。若订单创建或同事务券冻结失败，整个事务回滚，无可见 hold、claim、订单或成功幂等回执；若在 commit 之后外部支付创建失败，ORDER 依既有关闭/幂等补偿路径在新事务经同店 guard 释放临时占用及权益，不能把失败的外部请求伪装成未创建主单。支付超时、关闭、迟到支付退款仍按 SSOT 原规则。这样不放宽 06 的 NOT NULL，也不把跨模块本地事务误称为分布式全局事务。

改期使用同一已存在的 `orderId`，新 claim 先经 ROC-1 验证，再在同事务替换旧 claim/预约与 ORDER 排期快照和改期次数；失败原事实不动。既有规则只要求先锁新、失败留旧，并允许商家指派/改派；原指派不覆盖新时间的分支由**本轮已批 ROC-6** 冻结为 409 `SCHEDULE_SWAP_FAILED`，原预约与指派保留，不自动清空商家指派。若未来产品期望改期自动撤派、由商家重新指派，须另走 CCR 定义订单展示与流程；不能由 SCH 自作主张。

## 6. ROC-5：ORDER 指派完整性与保护生命周期

ORDER 是最终当前指派的唯一权威。`pet_order.service_staff_id` 与 `order_staff_assignment` 唯一 `is_current=1` 行须同时为空或同时为同一个员工；历史行不参与当前匹配。对当前指派查询建议增加内部 `OrderAssignmentProtectionApi`：

```text
getByReservationIds(storeId, reservationIds)
  -> 每个输入 ID 恰好一项：UNBOUND_IN_CURRENT_TX / UNASSIGNED / ASSIGNED /
     INCONSISTENT；orderId?, currentStaffId?, orderStage, verificationStatus,
     refundStatus?, orderVersion, assignmentVersion?, protectRequired

listCurrentByStore(storeId, affectedStaffIds?)
  -> complete=true, source=PRIMARY_CURRENT_READ, storeId, totalCurrentCount,
     items[{orderId,reservationId,storeId,serviceId,currentStaffId,
            orderStage,verificationStatus,refundStatus?,
            orderVersion,assignmentVersion,protectRequired}]
```

`affectedStaffIds` 仅用于补充目标员工索引查询，**不能代替全店一致性检查**：ORDER 在同一 guard 内先按 store 枚举全部当前指派并校验两套当前事实、归属、`reservation_id` 双向绑定和总数，再给目标员工子集；空列表也必须能证明是一次成功完整读取。分页只可在同一 guard/同一事务当前读中完成，最后校验总数与覆盖，不把第一页当完整。需要的物理候选为 ORDER 侧 `(store_id,order_stage,id)`、当前指派以 `staff_id,is_current` 和 `order_id,is_current` 为入口；`order_staff_assignment` 无 `store_id` 时由 `pet_order` 归属联接，具体 EXPLAIN 和索引 DDL 后续交 ORDER Writer。SCH 不直读 ORDER/MER 表。

保护口径按**底层事实**，不用 DisplayOrderStatus 推测。正式枚举为 `OrderStage=PENDING_PAYMENT/PENDING_CONFIRM/PENDING_SERVICE/COMPLETED/CANCELED`、`VerificationStatus=UNVERIFIED/VERIFIED`、`AppointmentReservationStatus=TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED`、`RefundStatus=CREATED/PROCESSING/SUCCESS/FAILED/UNKNOWN`，见[技术基线 §4](../../../docs/02-architecture/05-技术基线-v0.6.md)与 06 号 Schema。`refundStatus?` 若返回，只能经 REFUND 公共 API 取其权威枚举；无退款单可为 null，读取失败或未知值不可映成 null。下表中的“未核销”专指 `UNVERIFIED`，不创造第三种核销状态。

| ORDER/预约组合 | 当前指派 `protectRequired` 与动作 |
|---|---|
| `PENDING_PAYMENT`、TEMP_LOCKED、无最终指派 | false；预约本身仍入 ROC-1 内部匹配。本候选不新增“待支付不可指派”的产品门禁。 |
| `PENDING_PAYMENT/PENDING_CONFIRM/PENDING_SERVICE`、`UNVERIFIED`、预约 TEMP_LOCKED/CONFIRMED | 若已存在当前指派则 true 并固定该员工；无指派仍入内部匹配。是否允许在待支付阶段执行商家指派由 ORDER 既有动作权限另定，不因保护表放开。售后、退款申请、退款单创建或退款处理中不得提前解除 claim。 |
| `CANCELED` 但预约尚 TEMP_LOCKED/CONFIRMED（例如退款成功释放事件未消费） | 当前指派仍 true、预约仍计占用，直到 SCH 提交 RELEASED/EXPIRED；不得因订单显示退款中/已退款就提前放人。 |
| `COMPLETED` 且 `VERIFIED` | 当前指派保留为历史与评价依据，但不再要求未来人员保护；若仍有未来区间的有效 claim，视不一致 503，先对账。历史窗占用/关闭规则仍按 SCH 状态。 |
| `CANCELED` 且预约已 RELEASED/EXPIRED | 当前指派可以留作历史，`protectRequired=false`；若取消原因/退款事实与释放不符，由 ORDER/SCH 对账，不借此恢复订单。 |
| `PENDING_CONFIRM/PENDING_SERVICE` 且 `UNVERIFIED` 却已 RELEASED/EXPIRED；`COMPLETED` 且 `UNVERIFIED`；当前指派找不到对应订单/预约；两份当前人员不一致；未知状态或无可证明 claim | `INCONSISTENT`，全量查询报 503；不得将其排除为“零在途”，也不得通过减员。`CANCELED` 且 `VERIFIED` 不在此列：已核销后退款可能保留已核销事实，需按退款/预约事实判定。 |

表中 `CANCELED` 且 claim 仍有效的保护是**容量释放滞后期间的保守技术口径**，不改变订单退款/核销资格；既有“refund_order 创建后禁止核销”继续由 ORDER 自己执行。若 ORDER 发现 `COMPLETED/VERIFIED` 与未来 claim 的组合，不能通过自动改订单状态化解。`protectRequired` 是内部服务资源保护位，不是新的展示订单状态。

商家指派或改派按同一 requestId 幂等并取得 guard，ORDER 锁订单当前行，先检验可指派阶段和员工所属店/在职在岗/具体服务能力/两个完整时段，再以新员工为固定值重跑 ROC-1；成功时同事务更新 `pet_order.service_staff_id`、旧/新 `order_staff_assignment.is_current`、版本、审计与幂等回执。已指派员工被减排班、撤销能力或 MER 停用时，即使总人数仍够，也须先由商家合法改派；人员离职/停用不可直接把未完成订单指派置空（最终商家 PRD §5.6）。ORDER/MER 对相应写路径同样先取 guard，不能先锁自己的行再等待 guard。履约终态和退款释放异步事件各自幂等、在 guard 内复核，允许短暂保守占用但不得提前释放。

**并发反例**：T1 改派订单从甲到乙，T2 关闭乙的排班。二者同店 guard 串行；若 T1 先提交，T2 按 ORDER 全量当前指派读出乙并 409；若 T2 先提交，T1 复查乙资格失败并 409。T3 退款成功事件把预约从 CONFIRMED 改 RELEASED 时若订单仍 `PENDING_SERVICE`，后续减员查询应报 503 要求对账，而不能因活跃 claim 消失就漏掉指派。无 guard、旧快照或只按活跃 reservationIds 反查均无法证明这三个结果。

## 7. ROC-6：改期后的当前指派（新增已批准产品选择）

订单改期在新时间继续保留商家当前指派，并把该员工作为 ROC-1 固定输入；员工若不覆盖新 GENERAL 或两条接送完整 claim，交换返回 `SCHEDULE_SWAP_FAILED`，原预约、原 claim、当前指派、改期次数与 30 分钟确认周期均不变。不能先写新排期再要求商家补人，也不能因另有合格但未指派员工而暗中改派。这个分支会限制用户可选的改期时间，属于**本轮已批准的新增产品选择**；若未来改为自动撤派，需另走 CCR 定义订单人员字段、商家通知和重新指派时限。本裁决不改变“一单最多一次、同商家同服务、预约开始前、先锁新失败留旧”的旧规则。

## 8. 分期交付与验收门禁

1. **联合合同已批准**：ROC-1～6 的逻辑合同与产品选择由用户批准；SCH-003、ORDER、MER、TX/客户端 Owner 仍须按 36 号同步各自实现。批准不使本候选自动变成已调用 API。
2. **合同同步**：唯一 Writer 增量同步 06/07/10/11/12 和 34 号后续版本：选窗字段、当前指派公共 API、claim 基数、绑定时序、错误与 `x-contract-status`；当前六字段可约 GET 不应冒称已经输出 windowId/kind 或已证明接送组合。对 C 的单窗 `available/remainingCapacity` 与成对接送可行性区分作客户端契约测试，hold 仍为最终授权。
3. **迁移和读保护**：按 34 号先盘点旧 GENERAL/未完整 claim、ORDER 两份当前指派不一致、已释放预约仍在途订单；建 guard、claim 与索引并回填经校验的数据。不能恢复的门店/服务继续隔离受影响发布与减员；回滚不得丢失旧窗和订单快照。
4. **实现路径**：先交 ORDER 全量完整查询与共同 guard 接入、SCH 当前事实/精确求解和 MySQL 多连接证明；再交 hold/create 原子绑定、GENERAL 关联、双 claim hold/confirm/release/swap；随后接商家指派/改派、MER 减员及可约 GET/前端字段。每一依赖未到位的入口维持已批失败关闭。
5. **门禁证据**：按 [SCH-003 测试映射](../../issues/wave-3/SCH-003-contract/REVIEW-TEST-MAP.md)跑真实 MySQL 并发、回滚、索引/性能、幂等、存量迁移和客户端合同；检查无 biz→biz、跨域持久层访问，列明未执行项与残余风险。没有这些证据不得标 SCH-003/ORDER DONE、合并或生产启用。
