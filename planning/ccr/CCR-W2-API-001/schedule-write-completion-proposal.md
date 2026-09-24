# 排期写入 G1–G3 契约补齐提案（SCH-004）

状态：**PROPOSED / 重大技术契约待人工批准**。日期：2026-09-24。本文只给出可评审候选，不是已冻结的 07/10/11/12/Schema 契约，不授权迁移、业务实现、开关开启或 PR 合并。

依据顺序：SSOT §12、§13、§29；最终 C/商家 PRD；技术基线；06 号 Schema、07/10/11/12/23 号 API/幂等契约；[联合审阅回执](schedule-review-decisions.md)与[写入提案 v0.2](schedule-write-proposal.md)。SSOT §29 已批准：容量取配置与合格人员数的较小值、人员排班和能力撤销须保护既有预约及指派、接送两方向分窗、能力过期编辑冲突、事实缺失失败关闭。**保护算法、持久化形状、跨域锁协议和接送占用区间没有获批**；本文不重新裁决产品规则。

## 1. 已核实的缺口和实施边界

| 事实 | 当前证据 | 对写入的影响 |
|---|---|---|
| `schedule_reservation` 仅有 `start_at/end_at`、`pickup_start_at/return_start_at`，无双方向结束时间、window ID 或分方向占用行 | 06 号 `schedule_reservation`；07 号 §6.2 hold command；10 号 §3.5 创建订单 | 不能仅凭 `return_start_at` 推出 RETURN 占用区间，更不能把一个主区间无差别计入两个方向。 |
| 商家最终 PRD §5.4 明确上门接送分别占用上门与送回预约时段、到店按服务时长占用对应区间；仅定义送回开始 ≥ 上门开始 +120 分钟，未给接送两段固定时长 | 商家 PRD 正文“排期履约”“上门接送型”；SSOT §12/§29 | 预约所选时间窗与实际占用区间的映射须和 SCH-003/订单一起冻结；不能套固定 60 分钟或擅用服务时长作接送段时长。 |
| `order_staff_assignment` 与 `pet_order.service_staff_id` 在 ORDER 域，07 号没有按员工/预约读当前指派的权威 API；MER disable 在 27 号 §6.1 仍阻塞 | 06 号订单表/指派表；07 号 §7.1；27 号 §6.1 | SCH 不得读 ORDER Repository/Mapper，不能以“未查到”当无人被指派；MER 停用同样不得绕过共同协议。 |
| `staff_service_capability` 只有明细行和唯一 `(staff_id,service_id)`，无集合版本；新员工零行与旧编辑的空集合不可区分 | 06 号能力表 | 请求幂等键无法替代编辑版本。 |
| SCH-001 当前按单一 `start/end` 重叠统计占用，查询无 kind；这是展示投影 | 07 号 §6.1.1；10 号 §3.4 | G3 同步前不能以旧统计证明接送守卫正确。 |
| 10 号创建订单流程先调用 `ScheduleCommandApi.hold` 再 `OrderCommandApi.create`，而 06 号 `schedule_reservation.order_id` 当前为 NOT NULL | 07 号 §6.2、10 号 §3.5、06 号预约表 | SCH-003/TX-001 须解释预生成订单 ID 或同事务关联时序；claim/指派 DTO 不能擅自假定临时 hold 当下已经有完整 ORDER 事实。 |

本提案涉及 SCH-004 写入、SCH-003 hold/confirm/swap/release、订单指派和 MER 员工停用的**共同契约**。所有模块仍只读写自己的持久层；跨域事实只通过各自 `*-api`。特别地，禁止 SCH 直读订单指派表或借一个共享数据库锁偷偷绕过模块 API 边界。

## 2. G1：共同闸门、人员保护及跨服务容量

### 2.1 稳定闸门候选（需批准）

建议由 SCH 拥有 `schedule_store_capacity_guard(store_id PK, version, updated_at)`。同一门店的容量相关写命令都必须在**同一主库 DataSource、同一顶层本地事务**中，经 `ScheduleCapacityGuardApi.acquire(storeIds)` 取得稳定行的排他锁。可用唯一键的原子 `insert-if-absent` 建首行，再以当前读取得锁；预检查开放窗或 `SELECT FOR UPDATE` 空结果均不构成闸门。锁直到顶层提交/回滚才释放，不以 JVM/Redis 锁或跨请求 lease 代替。

参与命令至少包括：SCH hold、confirm、swap、release/expire、窗口受保护修改、员工排班减少可用性、能力撤销；ORDER 指派/改派/撤派；MER 停用或使服务人员失去资格的后续命令。新命令若会改变预约/人员/指派事实，须先判定是否也要参加，不能出现旁路。纯展示查询无需拿闸门。已有 MER disable 保持原 `IMPLEMENTATION_BLOCKED`，直到它和 ORDER 指派一起遵守同协议。

调用顺序候选：① 23 号 Admission 绑定 `requestId`（外层无业务写事务）；② Execution 锁幂等记录并重验当前权限；③ 按 `storeId` 数值升序取得 SCH 闸门（跨店 swap/批量同序，不能先拿订单行再倒序取门店闸门）；④ 取得 SCH 自有窗口/预约当前事实；⑤ 经 ORDER 公共 API 取同事务当前指派和未完成订单事实，经 MER 公共 API 取当前员工资格，经 SERVICE 公共 API 核目标服务归属；⑥ 完整校验、各 Owner 写自身事实、审计和成功回执同顶层事务提交。确需先取另一业务锁的命令必须重排或证明全局无环，不能仅在 SCH-004 局部实现此顺序。

这些 `*-api` 事务内读必须加入调用方同一连接/事务，采用主库**当前读**并记录校验所需版本。现有独立只读 `REQUIRES_NEW`、另一个 DataSource、RR 旧一致性快照、缓存或远程 API 的一次查询，都不能作为锁内权威证明。若技术上无法共享该事务，须另起 durable guard/fencing 的 CCR；本候选不得假称已安全。`biz` 只依赖对方 `api`，不依赖另一个 `biz`。

事务锁竞争遵守 23 号有界等待；死锁、超时和连接断开整笔回滚，按**原 requestId/原参数**有界重试。提交 ACK 不明先查主库幂等结果，不能换键重做；同参成功重放先重验当前权限后返回旧成功回执，不重取业务闸门或再次更改容量。跨店 swap 的新旧预约在一个事务内按升序锁两店，先证明新预约，再释放旧预约；若业务上不允许跨店，合同应明确拒绝并减少此路径，不能隐含假设。

### 2.2 校验事实与职责（需与 SCH-003/ORDER 冻结）

建议 `ReservationCapacityClaimDTO` 成为 SCH 的权威只读投影：`claimId,reservationId,orderId?,storeId,serviceId,windowId,kind,startAt,endAt,reservationStatus,version`，时间为半开 `[startAt,endAt)`；`orderId?` 表示 hold 与订单绑定时序未冻结，不授权持久化 NULL 以绕开现有 NOT NULL。一个到店预约为 GENERAL，一个接送预约的 PICKUP/RETURN 各有明确 claim。`TEMP_LOCKED/CONFIRMED` 均受保护；`RELEASED/EXPIRED` 只有**已提交状态**才不再占用，不能根据 `lock_expire_at` 自行跳过仍为 TEMP_LOCKED 的行。`windowId` 保存原选窗身份，防止改窗目标或 kind 后失去关联。此 DTO/表是候选，G3 未冻结前不可实装。

ORDER 提供同事务公共查询，返回按 `orderId` 的**当前**指派、员工 ID、订单未完成/仍需保护的判断及版本，并明确 `pet_order.service_staff_id` 与 `order_staff_assignment.is_current` 的唯一权威关系。若两者矛盾、订单缺失或状态不明，返回依赖不可信，不能当未指派。MER 提供同事务员工 `storeId/employmentStatus/serviceEnabled/version` 当前事实；SCH 以自身能力、AVAILABLE 排班并集和目标服务匹配。接口不得泄露 Repository/DO/Entity。

ORDER 还须明确“当前指派需保护”的生命周期状态集合及它与 SCH `TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED` 的关系：例如当前指派尚在而 reservation 已 RELEASED，不能仅因无活跃 claim 就忽略此订单；这是对账异常还是合法存量状态须由 ORDER 契约作权威判断。未冻结前，涉及该员工的减少可用性失败关闭。

对候选变更，须同时验证：(a) 每个 claim 的原 `windowId/kind/服务` 与配置容量约束；(b) **整个** claim 时间段内，已指派员工仍在本店、在职在岗、具备该服务能力、排班无空档，且不能在重叠 claim 被重复使用；(c) 未指派 claim 在该店所有同时受影响服务之间存在人员配置的可行证据。一个预约的同一 claim 必须由**同一员工覆盖全段**，不能把各分钟独立匹配后中途换人。员工能做 A/B 两项服务，只是一名共享人员，不能各算一次完整容量。已指派订单不能因别的合格员工仍够多就撤掉被指派人的排班或能力。

候选算法不在 SCH-004 独自定稿。可评审的两种技术实现是：

1. **锁内完整可行性证明**：按所有相关 claim、员工资格及时间冲突建立约束；已指派固定，未指派为每个完整 claim 找一名员工，重叠 claim 不共用员工。端点切分仅用于识别时间冲突，不允许在段间更换人。证明成立才提交；无法在有界执行预算内证明则 503 失败关闭，不能伪报“容量已满”。此方案不加业务人数/服务数上限，但最坏复杂度与门店闸门持锁时间须性能验收。
2. **SCH 自有 durable 技术占位**：hold 时为 claim 持久记录暂定员工容量令牌，和 ORDER 最终指派明确区分；指派或排班变化须在闸门内保持/重排令牌，最终指派固定约束优先。只做贪心会把本来可行的预约误判成容量不足，因此仍须定义有界重排证明或将“不能证明”报 503。新增分配表、与订单指派对账/迁移和恢复更复杂；不得把暂定人员展示为商家已指派。接送两 claim 是否必须由同一名最终指派员工服务，需先核对并冻结 ORDER 语义，不能由算法暗定。

建议先批准**共同闸门与权威事实接口**，再由 SCH-003/ORDER 在独立性能与事务评审中选择 1 或 2；两者都不得引入“每人最多 200 项服务”之类未批准的业务限制。证明范围限受影响门店，从变更触及的 claim/员工出发，对“时间重叠且争用同一合格员工”的 claim 递归扩展到闭包；必要时覆盖该店全部待履约 claim，不能仅取首层相交或先按服务切分后各自求余量。允许实现内部超时/资源预算，超过时返回依赖不可用并监控，不把技术预算转成新预约业务上限。

### 2.3 失败关闭与前后例

| 场景 | 允许的结果 |
|---|---|
| 员工 X 同时具 A/B 能力；A 09:00–10:00 已占用，B 09:30–10:30 新 hold；本店只有 X | B 不得仅因 B 的合格人数=1 且 B 占用数=0 就成功。两 claim 重叠，共享 X，约束不可行。 |
| A 的已指派订单给 X；另有 Y 可做 A；商家撤 X 的 A 能力或缩短 X 排班 | 拒绝业务冲突；人数仍可能为 1 不等于已指派 X 可履约。先在订单域合法改派并提交，再重试原意图。 |
| 撤员检查后另一线程 hold/assign 同一店 | 两者争同一 SCH 闸门；先提交者形成另一方的当前事实，后者复核，不允许两边都按旧快照提交。 |
| ORDER 指派 API、MER 员工事实或 SCH claim 不可读/未知枚举，或双方向 claim 区间缺失 | 503 `COMMON_DEPENDENCY_UNAVAILABLE`，整笔回滚；不能返回空指派、零预约、零人员或 batch-close 的普通 blockedWindows。 |
| 已知预约/指派使保护不成立 | 409 业务冲突（具体 SCH 错误码由 12 号同步）；不允许部分写入。 |

## 3. G2：能力集合首次空集合 CAS（可单独冻结）

建议 SCH 自有 `staff_capability_set(staff_id PK, store_id, version, updated_at)` 作为集合头；`staff_service_capability` 保持 ENABLED 明细，空集合是零明细，**不是缺少版本**。GET 对尚无头的合法员工返回 `serviceIds=[]、version=0`，这是“尚未编辑”的逻辑版本，必须先成功核验员工归属，故障不能合成空集合。首次 PUT 带 `expectedVersion=0`，在同事务中以唯一键原子创建头并推进到 1、替换明细、写动作审计与幂等成功回执。第二个也读到 0 的编辑只能有一个成功；唯一冲突者返回 409 `COMMON_CONFLICT` 并提示重新 GET。已存在头时 `UPDATE ... WHERE version=expectedVersion` CAS；影响 0 行则冲突，不覆盖集合。

每次**新成功** PUT 版本递增，包括写入与当前相同的集合；同 requestId 同参数成功重放返回第一次版本，不再递增。写成空集合后头仍保留，不能删除头后把版本退回 0。`serviceIds` 是具体服务 ID 集合，重复项 400，跨店/不存在服务按既有服务归属规则拒绝；如将其声明为无序集合，23 号 canonical 摘要应按规范数值升序处理并固定版本。撤销项先进入 G1 保护；只增加项仍需版本 CAS、目标身份/资格校验和审计，不能以“安全增加”跳过集合冲突。独立集合头不借 `merchant_staff.version`、requestId 或明细行数代替。

Schema/API 候选：GET/PUT `/api/v1/merchant/staff/{staffId}/service-capabilities` 的回执增 `version`，PUT 必填 `expectedVersion`、`serviceIds`、撤销时 `reason`；Java 内部命令同字段。`version` 使用整型版本而非业务 ID；HTTP ID 仍为 String。集合头、明细与审计同顶层事务。此项可先完成契约同步/孤立 CAS 测试，但**含撤销的生产 PUT**在 G1 到位前保持失败关闭。

## 4. G3：PICKUP/RETURN 权威匹配、旧窗和存量

### 4.1 拟定匹配不变量（区间形状待批准）

- 到店预约只使用 GENERAL claim；接送预约必须有一条 PICKUP、一条 RETURN claim；二者分别指向同店同服务同 kind 的原 OPEN window。服务端 hold/swap 最终校验 `returnStart >= pickupStart+120分钟`，窗口管理不遍历组合。查询按 kind 给候选，单个方向的剩余容量不能代表两方向组合已锁定。
- 每条 claim 的 `[startAt,endAt)` 必须是经 SCH-003 冻结的**实际占用**区间，位于被选择窗口可约范围内；`pickupStart/returnStart` 与对应 claim 开始一致，结束时间必须有独立权威来源。若最终裁决为“选择整个开窗即占用整窗”，须明确这一选择；若可选择窗内子区间，则 hold DTO 须提供/推导准确结束值并校验。当前字段不足以自动推出答案，尤其不能补固定时长或用 `service_item.duration_minutes` 猜两次接送时长。
- 窗口 close、降容量、移时段或更改服务/kind 的保护同时检查**原窗口 ID**、旧区间和候选新区间；原 claim 不会因窗口目标改变而消失。建议窗口 `storeId/serviceId/kind` 创建后不可 PUT 改身份，确需变更走受保护关闭+新建；若批准允许原地改 kind，必须先证明原 claim 已清零，且新目标按同闸门校验。不同 kind 的计数不能互相借用。
- `schedule_reservation` 的状态变化与 claim 增删/状态投影同事务；SCH-001 读侧需同步 kind 与对应 claim 计数，避免旧单区间再次混用。若历史预约无法恢复两个权威 claim，不得开启该店/服务的接送新 hold 或受影响减员。

候选 Schema 为 SCH 自有 `schedule_reservation_claim(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at,...)`，按 `reservation_id+kind` 唯一（到店 GENERAL、接送 PICKUP/RETURN）；`schedule_availability_window.window_kind NOT NULL`、`schedule_store_capacity_guard`、G2 集合头、审计表列另成隔离迁移。具体外键/索引、旧预约迁移脚本、claim 是否引用原窗口版本与回滚策略，在 34 号 Schema 和 SCH-003 合同中一起评审。本提案不把这些候选 DDL 当可执行 SQL。

### 4.2 存量 GENERAL 处置和发布门禁

上线前按 `storeId/serviceId/fulfillmentType/window status/有效预约状态` 盘点已有 GENERAL 窗与预约，并形成可追溯清单。IN_STORE 的 GENERAL 可按已批语义保留，仍须检查其预约主区间与历史选窗能否建立正确关联。PICKUP_DELIVERY 的旧 GENERAL **不能简单复制为 PICKUP+RETURN 两组 OPEN 窗**：既无方向、也无第二占用区间；这会凭空多出可约供给。对有 TEMP_LOCKED/CONFIRMED 的旧窗保留历史占用且阻止受影响改写；对新接送预约先关闭旧 GENERAL 的可约投影，逐店服务核验后由获权商家设置两组真实时段。可可靠恢复旧预约 claim 的才迁移；无法恢复的继续隔离并走明确的数据处置/订单履约方案，不能用空 claim 默许关窗或减员。正式发布前应报告各类数量、未解决 ID、迁移校验与回滚路径。

## 5. 少量需要人工批准的重大契约

| 编号 | 请批准/退回的具体候选 | 不需重裁的已批语义 |
|---|---|---|
| SCHC-1 | SCH 自有每店稳定闸门；所有 hold/confirm/swap/release、ORDER 指派及 MER 停用等写方加入同主库事务，固定锁序；ORDER/MER 当前事实 API 与未完成状态口径同步。需共同评审事务装配、全路径参与和性能。 | 减员不得破坏预约/指派；故障失败关闭。 |
| SCHC-2 | SCH-003 定义 GENERAL/PICKUP/RETURN claim 的实际起止、选窗 ID、订单指派与接送两 claim 的关系；决定完整可行性证明或 durable 技术占位及其有界失败策略。需订单/排期 Owner 共审。 | 接送分窗、120 分钟、分钟级、用户不选人。 |
| SCHC-3 | SCH 独立集合头 version=0/首次 PUT 原子 CAS，GET/PUT 版本与迁移；此项可先单独冻结。 | 能力按具体服务项、过期冲突提示重读。 |
| SCHC-4 | 接送旧 GENERAL 盘点、隔离、可恢复 claim 的迁移与无法恢复时的发布阻断/逐单处置门禁。若需改变旧订单履约或对用户承诺，应另走产品裁决，不能通过技术迁移自动取消。 | 历史保留、存量预约不被写操作破坏。 |

上述均是**技术/数据契约**，不把约束求解或暂定分配私自升级为用户可选人员、固定排期槽或新的产品限制。人工作重大 Contract 审核按 WORK_EXECUTION_PROTOCOL §4；原用户批准的业务方向已经足够，不应要求重选。

## 6. 分期与禁止越界

1. **现在即可评审/冻结** G2 集合 CAS、接口形状和独立测试；不改已运行 Schema/代码。正向增加能力的实现只有在对应授权、审计、幂等及窗口读一致性完成后才可单独验收。
2. **G1/G3 未落地期间**：排班关闭/缩短/移动、能力撤销、已占用窗口 close/降容/改时段、接送新 hold 和依赖此事实的指派链保持相应实施门禁/失败关闭；可先准备 DTO、迁移草案、模拟故障与并发测试。不能用一次无预约查询解除门禁。已批准的批量关窗“业务阻挡进 blockedWindows”仍适用，但事实源故障整笔回滚并返回 503。
3. **联合同步后实施**：先 07/10/11/12/34 号权威契约和 ORDER/MER 对应 API，再隔离迁移、SCH/ORDER/MER 共同闸门装配、SCH-003 claim/预约写、SCH-004 受保护写，最后 SCH-001/002 查询和 M-002 页面联调。默认开关关闭；MySQL 实并发测试通过再考虑开启。未跑测试不标 DONE。

验收反例与锁序故障演练见 [SCH-004 G1–G3 审阅及测试映射](../../issues/wave-3/SCH-004-contract/REVIEW-TEST-MAP.md)。本轮仅生成文档，测试状态 **NOT_EXECUTED**，没有改动权威 Schema/API/Event、backend、SSOT 或共享台账。
