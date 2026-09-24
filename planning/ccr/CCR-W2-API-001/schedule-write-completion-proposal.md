# 排期写入 G1–G3 契约补齐提案（SCH-004）

状态：**SCHC-1～4 已批准 / ACCEPTED_CONTRACT_NOT_IMPLEMENTED**。日期：2026-09-24。批准回执见[四项技术裁决](schedule-write-completion-decisions.md)，权威补充见[34 号 API](../../../docs/04-api/34-Schedule-Protection-Contract-v0.1.md)与[34 号存储](../../../docs/03-database/34-Schedule-Protection-Storage-v0.1.md)。本文保留审阅推理和反例；不授权生产迁移、业务实现、开关开启或 PR 合并。

依据顺序：SSOT §12、§13、§29；最终 C/商家 PRD；技术基线；06 号 Schema、07/10/11/12/23 号 API/幂等契约；[联合审阅回执](schedule-review-decisions.md)与[写入提案 v0.2](schedule-write-proposal.md)。SSOT §29 已批准容量、人员保护、接送分窗和失败关闭；后续又批准本提案 SCHC-1～4 的锁、集合头、整窗 claim 与旧 GENERAL 处置。**跨服务完整容量求解、最终同人指派关系、hold/order 绑定细节和生产迁移仍未获批/未交付**。

## 1. 已核实的缺口和实施边界

| 事实 | 当前证据 | 对写入的影响 |
|---|---|---|
| `schedule_reservation` 仅有 `start_at/end_at`、`pickup_start_at/return_start_at`，无双方向结束时间、window ID 或分方向占用行 | 06 号 `schedule_reservation`；07 号 §6.2 hold command；10 号 §3.5 创建订单 | 不能仅凭 `return_start_at` 推出 RETURN 占用区间，更不能把一个主区间无差别计入两个方向。 |
| C 端最终 PRD §5.1 让用户分别“选择一个上门时段”和一个送回时段，并给出上门 10:00–11:00、送回 12:00–13:00 的整窗例子；商家最终 PRD §5.4 明确两个方向分别占用预约时段，到店按服务时长占用对应区间 | C 端 PRD“预约上门时间选择”“业务规则”“临界点计算”；商家 PRD“排期履约”及 §5.4；SSOT §12/§29 | **推荐**所选候选窗口整段即该方向占用 `[window.start,window.end)`；现有请求没有两个窗口 ID/结束时间，须由 SCH-003 同步。窗内任意子区间选择无权威依据，不能顺手实现。 |
| `order_staff_assignment` 与 `pet_order.service_staff_id` 在 ORDER 域，07 号没有按员工/预约读当前指派的权威 API；MER disable 在 27 号 §6.1 仍阻塞 | 06 号订单表/指派表；07 号 §7.1；27 号 §6.1 | SCH 不得读 ORDER Repository/Mapper，不能以“未查到”当无人被指派；MER 停用同样不得绕过共同协议。 |
| `staff_service_capability` 只有明细行和唯一 `(staff_id,service_id)`，无集合版本；新员工零行与旧编辑的空集合不可区分 | 06 号能力表 | 请求幂等键无法替代编辑版本。 |
| SCH-001 当前按单一 `start/end` 重叠统计占用，查询无 kind；这是展示投影 | 07 号 §6.1.1；10 号 §3.4 | G3 同步前不能以旧统计证明接送守卫正确。 |
| 10 号创建订单流程先调用 `ScheduleCommandApi.hold` 再 `OrderCommandApi.create`，而 06 号 `schedule_reservation.order_id` 当前为 NOT NULL | 07 号 §6.2、10 号 §3.5、06 号预约表 | SCH-003/TX-001 须解释预生成订单 ID 或同事务关联时序；claim/指派 DTO 不能擅自假定临时 hold 当下已经有完整 ORDER 事实。 |

本提案涉及 SCH-004 写入、SCH-003 hold/confirm/swap/release、订单指派和 MER 员工停用的**共同契约**。所有模块仍只读写自己的持久层；跨域事实只通过各自 `*-api`。特别地，禁止 SCH 直读订单指派表或借一个共享数据库锁偷偷绕过模块 API 边界。

## 2. G1：共同闸门、人员保护及跨服务容量

### 2.1 已批准的稳定闸门设计（未实现）

已批准由 SCH 拥有 `schedule_store_capacity_guard(store_id PK, version, updated_at)`。同一门店的容量相关写命令都必须在**同一主库 DataSource、同一顶层本地事务**中，经 `ScheduleCapacityGuardApi.acquire(storeIds)` 取得稳定行的排他锁。可用唯一键的原子 `insert-if-absent` 建首行，再以当前读取得锁；预检查开放窗或 `SELECT FOR UPDATE` 空结果均不构成闸门。锁直到顶层提交/回滚才释放，不以 JVM/Redis 锁或跨请求 lease 代替。

参与命令至少包括：SCH hold、confirm、swap、release/expire、窗口受保护修改、员工排班减少可用性、能力撤销；ORDER 指派/改派/撤派；MER 停用或使服务人员失去资格的后续命令。新命令若会改变预约/人员/指派事实，须先判定是否也要参加，不能出现旁路。纯展示查询无需拿闸门。已有 MER disable 保持原 `IMPLEMENTATION_BLOCKED`，直到它和 ORDER 指派一起遵守同协议。

调用顺序候选：① 23 号 Admission 绑定 `requestId`（外层无业务写事务）；② Execution 锁幂等记录并重验当前权限；③ 按 `storeId` 数值升序取得 SCH 闸门（一般跨资源命令和批量同序，不能先拿订单行再倒序取门店闸门）；④ 取得 SCH 自有窗口/预约当前事实；⑤ 经 ORDER 公共 API 取同事务当前指派和未完成订单事实，经 MER 公共 API 取当前员工资格，经 SERVICE 公共 API 核目标服务归属；⑥ 完整校验、各 Owner 写自身事实、审计和成功回执同顶层事务提交。确需先取另一业务锁的命令必须重排或证明全局无环，不能仅在 SCH-004 局部实现此顺序。

这些 `*-api` 事务内读必须加入调用方同一连接/事务，采用主库**当前读**并记录校验所需版本。现有独立只读 `REQUIRES_NEW`、另一个 DataSource、RR 旧一致性快照、缓存或远程 API 的一次查询，都不能作为锁内权威证明。若技术上无法共享该事务，须另起 durable guard/fencing 的 CCR；本候选不得假称已安全。`biz` 只依赖对方 `api`，不依赖另一个 `biz`。

事务锁竞争遵守 23 号有界等待；死锁、超时和连接断开整笔回滚，按**原 requestId/原参数**有界重试。提交 ACK 不明先查主库幂等结果，不能换键重做；同参成功重放先重验当前权限后返回旧成功回执，不重取业务闸门或再次更改容量。V1 改期依 SSOT §3、C 端最终 PRD §5.1.19：同订单、同商家、同服务，先锁新时段再释放旧时段；本切片仅按原门店实施/测试，不增加跨店改期入口。多资源升序锁只是通用死锁预防协议，不授权跨店业务。

### 2.2 校验事实与职责（需与 SCH-003/ORDER 冻结）

建议 `ReservationCapacityClaimDTO` 成为 SCH 的权威只读投影：`claimId,reservationId,orderId?,storeId,serviceId,windowId,kind,startAt,endAt,reservationStatus,version`，时间为半开 `[startAt,endAt)`；`orderId?` 表示 hold 与订单绑定时序未冻结，不授权持久化 NULL 以绕开现有 NOT NULL。一个到店预约为 GENERAL，一个接送预约的 PICKUP/RETURN 各有明确 claim。`TEMP_LOCKED/CONFIRMED` 均受保护；`RELEASED/EXPIRED` 只有**已提交状态**才不再占用，不能根据 `lock_expire_at` 自行跳过仍为 TEMP_LOCKED 的行。`windowId` 保存原选窗身份，防止改窗目标或 kind 后失去关联。此 DTO/表是候选，G3 未冻结前不可实装。

ORDER 提供同事务公共查询，返回按 `orderId` 的**当前**指派、员工 ID、订单未完成/仍需保护的判断及版本，并明确 `pet_order.service_staff_id` 与 `order_staff_assignment.is_current` 的唯一权威关系。若两者矛盾、订单缺失或状态不明，返回依赖不可信，不能当未指派。MER 提供同事务员工 `storeId/employmentStatus/serviceEnabled/version` 当前事实；SCH 以自身能力、AVAILABLE 排班并集和目标服务匹配。接口不得泄露 Repository/DO/Entity。

ORDER 还须明确“当前指派需保护”的生命周期状态集合及它与 SCH `TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED` 的关系：例如当前指派尚在而 reservation 已 RELEASED，不能仅因无活跃 claim 就忽略此订单；这是对账异常还是合法存量状态须由 ORDER 契约作权威判断。未冻结前，涉及该员工的减少可用性失败关闭。

本轮建议冻结的**公共接口字段候选**（名称可由 07 号统一，但含义不能缺）：

| Owner API | 请求 | 权威回执/事务要求 |
|---|---|---|
| `ScheduleCapacityGuardApi.acquire` | `storeIds: String[]`，按数值去重升序；现有 `CommandContext` 由执行命令持有 | `void`；只在调用方顶层事务内建立/锁 SCH 自有 guard 行，调用返回不释放锁；事务不存在或 DataSource 不同即拒绝。 |
| SCH claim 当前事实 | `storeId,fromAt,toAt` 加受影响 `windowIds/staffIds`，时间半开 | 每条 `claimId,reservationId,orderId?,serviceId,windowId,kind,startAt,endAt,status,version`；明确返回“查询完整”标记，不能将超时/未知状态折算成空列表。锁后主库当前读。 |
| `OrderAssignmentFactsApi.listByReservationIds` | `reservationIds: String[]`，含所有受影响活跃 claim 的预约 ID | 每个 ID 对应 `bindingState,orderId?,currentStaffId?,protectRequired,orderVersion?,assignmentVersion?`；`UNBOUND_HOLD` 与已绑定但无指派须有不同的权威状态，不能用缺行猜测。ORDER 对当前指派表与订单快照不一致报故障。锁内参加同一事务并当前读。 |
| `MerchantCapacityStaffFactsApi.listByStoreAndStaffIds` | `storeId,staffIds: String[]`，含候选及当前指派人员 | 每个 ID 对应 `staffId,storeId,employmentStatus,serviceEnabled,version`；无该员工和状态未知明确区分。锁内参加同一事务并当前读；SCH 自己求能力与排班，不让 MER 推算容量。 |

这些字段仍是跨域 CCR 候选，不声称现有 `MerchantStoreStaffFactsApi` 的 SCH-002 展示查询已经提供事务锁内证明。未冻结的 hold→order 绑定时序会决定 `bindingState` 的物理实现；不能先放宽 06 号 `order_id NOT NULL` 来凑接口。

对候选变更，未来容量证明至少须同时覆盖：(a) 每个 claim 的原 `windowId/kind/服务` 与配置容量约束；(b) **整个** claim 时间段内，已指派员工仍在本店、在职在岗、具备该服务能力、排班无空档，且不能在重叠 claim 被重复使用；(c) 未指派 claim 在该店所有同时受影响服务之间有可行人员安排。一个 claim 不能靠“每分钟分别有人”拼出中途换人的假证明；员工能做 A/B 两项服务仍只是一名共享人员；有替代人员也不能撤掉已指派 X 的资格。

**本次不申请批准求解算法。**全局可行性证明、是否持久记录技术占位、接送两 claim 与一个最终指派的关系、性能预算与任何需要的暂定分配表，交 SCH-003/ORDER 后续独立 CCR，附真实多连接并发与容量反例再审。该 CCR 未冻结之前，缺少保护证明的减员/接送 hold 等相关写入按 SSOT §29 **失败关闭**；这是既有已批规则的执行边界，不再次向用户索取批准。不得以逐服务人数减占用、逐分钟独立匹配、纯贪心或未批准的“每人最多 200 项”上限代替证明。

### 2.3 失败关闭与前后例

| 场景 | 允许的结果 |
|---|---|
| 员工 X 同时具 A/B 能力；A 09:00–10:00 已占用，B 09:30–10:30 新 hold；本店只有 X | B 不得仅因 B 的合格人数=1 且 B 占用数=0 就成功。两 claim 重叠，共享 X，约束不可行。 |
| A 的已指派订单给 X；另有 Y 可做 A；商家撤 X 的 A 能力或缩短 X 排班 | 拒绝业务冲突；人数仍可能为 1 不等于已指派 X 可履约。先在订单域合法改派并提交，再重试原意图。 |
| 撤员检查后另一线程 hold/assign 同一店 | 两者争同一 SCH 闸门；先提交者形成另一方的当前事实，后者复核，不允许两边都按旧快照提交。 |
| ORDER 指派 API、MER 员工事实或 SCH claim 不可读/未知枚举，或双方向 claim 区间缺失 | 503 `COMMON_DEPENDENCY_UNAVAILABLE`，整笔回滚；不能返回空指派、零预约、零人员或 batch-close 的普通 blockedWindows。 |
| 已知预约/指派使保护不成立 | 409 业务冲突（具体 SCH 错误码由 12 号同步）；不允许部分写入。 |

事务前后例：T1 缩短 X 的 09:00–11:00 排班，T2 为依赖 X 的服务在 10:00–11:00 hold。若 T1 先取门店 guard 并提交，T2 随后在锁内读取缩短后的排班而拒绝；若 T2 先提交 TEMP_LOCKED claim，T1 随后读取该 claim 和 ORDER/MER 当前事实而拒绝。两种顺序均不得出现“hold 成功且 X 排班已缩短”。T1 查询到依赖故障时整个执行事务回滚，T2 可在随后正常复核；不能把故障包装成“该窗已被业务阻挡”的部分关闭结果。

## 3. G2：已批准的能力集合首次空集合 CAS（未实现）

已批准 SCH 自有 `staff_capability_set(staff_id PK, store_id, version BIGINT NOT NULL, updated_at)` 作为集合头；Java 版本为非负 `long`，HTTP/JSON 的 `version` 与 `expectedVersion` 为非负 Long 的十进制 **String**，沿用 27 号 §3 的外部版本规则和 23 号规范参数处理，绝不经 JavaScript `Number` 中转。`staff_service_capability` 保持 ENABLED 明细，空集合是零明细，**不是缺少版本**。GET 对尚无头的合法员工返回 `serviceIds:[],version:"0"`，这是“尚未编辑”的逻辑版本，必须先成功核验员工归属，故障不能合成空集合。首次 PUT 带 `expectedVersion:"0"`，在同事务中以唯一键原子创建头并推进到 1、替换明细、写动作审计与幂等成功回执。第二个也读到 0 的编辑只能有一个成功；唯一冲突者返回 409 `COMMON_CONFLICT` 并提示重新 GET。已存在头时 `UPDATE ... WHERE version=expectedVersion` CAS；影响 0 行则冲突，不覆盖集合。

每次**新成功** PUT 版本递增，包括写入与当前相同的集合；同 requestId 同参数成功重放返回第一次版本，不再递增。写成空集合后头仍保留，不能删除头后把版本退回 0。`serviceIds` 是具体服务 ID 集合，重复项 400，跨店/不存在服务按既有服务归属规则拒绝；如将其声明为无序集合，23 号 canonical 摘要应按规范数值升序处理并固定版本。撤销项先进入 G1 保护；只增加项仍需版本 CAS、目标身份/资格校验和审计，不能以“安全增加”跳过集合冲突。独立集合头不借 `merchant_staff.version`、requestId 或明细行数代替。

Schema/API 候选：GET/PUT `/api/v1/merchant/staff/{staffId}/service-capabilities` 的回执增 String `version`，PUT 必填 String `expectedVersion`、String[] `serviceIds`、撤销时 `reason`；Java 内部命令/回执的版本用 `long`，DB 用 BIGINT，序列化/反序列化必须在 `9007199254740993`（2^53+1）仍精确往返并可 CAS。版本上溢应拒绝并告警，不回绕；它不是业务 ID，但在 HTTP 上同样不能作为 JSON number。集合头、明细与审计同顶层事务。此项可先完成契约同步/孤立 CAS 测试，但**含撤销的生产 PUT**在 G1 到位前保持失败关闭。

## 4. G3：PICKUP/RETURN 权威匹配、旧窗和存量

### 4.1 已批准整窗占用与选窗 ID（未实现）

- **推荐的明确口径**：C 端最终 PRD §5.1 的原话是“用户必须选择一个上门时段，作为接宠时间窗口”，送回“同样可选择日期，展示商家按分钟精度自定义的可用送回时段”；临界例为上门 **10:00–11:00**、最早送回 **12:00–13:00**。商家 PRD §5.4 又规定上门与送回“分别占用”预约时段。因此一个接送预约各选一条 OPEN PICKUP/RETURN 窗，**两条所选候选窗的完整 `[window.start,window.end)` 分别成为占用区间**。这沿用现有“选一个时段”的产品行为；只需补上当前 API/Schema 缺少的所选窗口身份和分方向权威占用事实，不引入固定时长。
- SCH-003 的 hold/swap 技术命令后续须增 `selectedPickupWindowId`、`selectedReturnWindowId`（跨模块/HTTP ID 为 String）；在同一闸门事务核验两窗同店同服务、分别为 PICKUP/RETURN 且 OPEN，`pickupStart == pickupWindow.startAt`、`returnStart == returnWindow.startAt`，把两窗当时的完整边界和 ID 冻结为两条 claim。结束时间来自选中的权威窗口，不让客户端另报或用 `service_item.duration_minutes` 猜测。当前 C 端查询只有六字段，无 `windowId/kind`，须由 SCH-004/SCH-003 后续联合增列才能供客户端传两个 ID；现有 SCH-001/002 不具备此能力。若前端日后要在窗口内任意截取子区间，那是目前 PRD 未写的**另一种选择行为**，须另行产品裁决和新的结束时间契约，不由本次技术同步暗加。
- 到店预约只使用 GENERAL claim，继续按最终商家 PRD 的服务时长/已选时间区间约束处理；本提案不擅改其已批时间语义。接送两 claim 分别指向同店同服务同 kind 原窗。服务端 hold/swap 最终校验 `returnStart >= pickupStart+120分钟`，窗口管理不遍历组合。查询按 kind 给候选，单个方向的剩余容量不能代表两方向组合已锁定。
- 窗口 close、降容量、移时段或更改服务/kind 的保护同时检查**原窗口 ID**、旧区间和候选新区间；原 claim 不会因窗口目标改变而消失。建议窗口 `storeId/serviceId/kind` 创建后不可 PUT 改身份，确需变更走受保护关闭+新建；若批准允许原地改 kind，必须先证明原 claim 已清零，且新目标按同闸门校验。不同 kind 的计数不能互相借用。
- `schedule_reservation` 的状态变化与 claim 增删/状态投影同事务；SCH-001 读侧需同步 kind 与对应 claim 计数，避免旧单区间再次混用。若历史预约无法恢复两个权威 claim，不得开启该店/服务的接送新 hold 或受影响减员。

已批逻辑存储包括 SCH 自有 `schedule_reservation_claim(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at,...)`，按 `reservation_id+kind` 区分到店 GENERAL 与接送 PICKUP/RETURN；`schedule_availability_window.window_kind NOT NULL`、`schedule_store_capacity_guard`、G2 集合头及审计另见[34 号存储补充](../../../docs/03-database/34-Schedule-Protection-Storage-v0.1.md)。具体索引/DDL、旧预约迁移脚本与回滚策略尚未完成，本提案不把逻辑字段当可执行 SQL。

### 4.2 存量 GENERAL 处置和发布门禁

上线前按 `storeId/serviceId/fulfillmentType/window status/有效预约状态` 盘点已有 GENERAL 窗与预约，并形成可追溯清单。IN_STORE 的 GENERAL 可按已批语义保留，仍须检查其预约主区间与历史选窗能否建立正确关联。PICKUP_DELIVERY 的旧 GENERAL **不能简单复制为 PICKUP+RETURN 两组 OPEN 窗**：既无方向、也无第二占用区间；这会凭空多出可约供给。对有 TEMP_LOCKED/CONFIRMED 的旧窗保留历史占用且阻止受影响改写；对新接送预约先关闭旧 GENERAL 的可约投影，逐店服务核验后由获权商家设置两组真实时段。可可靠恢复旧预约 claim 的才迁移；无法恢复的继续隔离并走明确的数据处置/订单履约方案，不能用空 claim 默许关窗或减员。正式发布前应报告各类数量、未解决 ID、迁移校验与回滚路径。

## 5. 四项已批技术契约摘要

| # | 已批准设计 | 实施后影响的真实行为 | 先前业务裁决 |
|---|---|---|---|
| SCHC-1 共同锁与事实 | SCH 自有每店稳定闸门；SCH hold/confirm/swap/release、SCH-004 受保护写、ORDER 指派、MER 停用在同主库同顶层事务中按固定序取锁。ORDER/MER 经公共 API 提供当前指派、未完成状态、员工资格和版本。 | 排班/能力减少与新预约或指派竞态时只会有一个顺序生效；事实缺失仍拒绝执行。MER disable 继续保持阻塞，直到加入协议。 | 已有预约/指派必须保护、读取失败关闭；不要求再批准“先失败关闭”。 |
| SCHC-2 能力编辑版本 | SCH 独立集合头 BIGINT `version`，首个空集合逻辑版本 0；首次 PUT 原子 CAS；HTTP `version/expectedVersion` 为十进制 String。 | 两个编辑者同时从空集合开始，仅一个成功，另一人看到冲突并重读；大于 2^53 的版本不会在页面丢精度。 | 能力按具体服务项；过期编辑不得静默覆盖。 |
| SCHC-3 接送选窗事实 | 按 C/商家最终 PRD 的“选一个时段”采用**完整所选 PICKUP 和 RETURN 窗**占用；SCH-003 hold/swap 增两个 selected windowId，并在同事务持久两条分方向 claim。 | 选 10:00–11:00 上门及 12:00–13:00 送回时，两整窗分别锁定；改旧窗不能丢失原占用。窗内任意子区间不随本次上线。 | 两方向分窗、120 分钟、分钟级；用户不选人员。 |
| SCHC-4 存量接送窗 | 上线前盘点旧 GENERAL；到店可核对保留，接送旧 GENERAL 先隔离新预约，能可靠恢复的旧占用才迁移；无法恢复则阻断相关发布和减员，逐单处置另案。 | 不会把一个旧接送窗自动复制成两份可约供给，也不会凭空释放已有订单的占用。 | 历史保留、存量履约不被排期写入破坏；本项不授权自动取消订单。 |

本轮**没有批准容量求解算法**。跨服务共享人员的完整证明/技术占位、两 claim 与一个最终指派的具体关系、性能边界，交 SCH-003/ORDER 后续独立 CCR。它未冻结时受影响减少动作失败关闭已由 SSOT §29 批准。四项批准详情以[回执](schedule-write-completion-decisions.md)和两份 34 号补充为准。

## 6. 分期与禁止越界

1. **已批准、待实施** G2 集合 CAS 与接口形状；不改已运行 Schema/代码。正向增加能力的实现只有在对应授权、审计、幂等及窗口读一致性完成后才可单独验收。
2. **G1/G3 未落地期间**：排班关闭/缩短/移动、能力撤销、已占用窗口 close/降容/改时段、接送新 hold 和依赖此事实的指派链保持相应实施门禁/失败关闭；可先准备 DTO、迁移草案、模拟故障与并发测试。不能用一次无预约查询解除门禁。已批准的批量关窗“业务阻挡进 blockedWindows”仍适用，但事实源故障整笔回滚并返回 503。
3. **分段实施**：四项已在 34 号 API/存储补充记录，07/10/11/12 的正式增量、ORDER/MER 对应 API、隔离迁移与共同闸门装配仍待实现切片；SCH-003/ORDER 再以独立 CCR 冻结跨服务容量证明、接送两 claim 与最终指派的关系及性能边界，之后才能实现/启用受影响减员、接送 hold 和最终指派链。SCH-001/002 查询及 M-002 页面随权威事实联调。默认开关关闭；MySQL 实并发测试通过再考虑开启。未跑测试不标 DONE。

验收反例与锁序故障演练见 [SCH-004 G1–G3 审阅及测试映射](../../issues/wave-3/SCH-004-contract/REVIEW-TEST-MAP.md)。业务测试状态 **NOT_EXECUTED**；现有 06/07/10/11/12、backend、SSOT 与共享台账尚未因四项批准而变化。
