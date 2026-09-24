# 排期保护存储映射 v0.1

状态：**ACCEPTED_STORAGE_DESIGN / DDL_AND_MIGRATION_NOT_EXECUTED**（SCHC-1～4 的逻辑事实获批，物理脚本、索引、回填及生产迁移均未交付）。2026-09-24。来源：[批准回执](../../planning/ccr/CCR-W2-API-001/schedule-write-completion-decisions.md)，配套[34 号 API 补充](../04-api/34-Schedule-Protection-Contract-v0.1.md)。现行 06 号核心 Schema 仍是当前表结构事实；本文不提供可执行 DDL，不分配 Flyway 版本，也不宣称新表/列在任何生产库存在。

## 1. 既有表和新增事实的边界

| 06 号既有表 | 当前已存事实 | 本补充要求的逻辑变化 |
|---|---|---|
| `schedule_availability_window` | store/service/start/end/configured_capacity/status/version；无 kind | 增 `window_kind=GENERAL/PICKUP/RETURN`，归属及 kind 创建后固定；OPEN 同店同服务同 kind 不相交。旧行被解释为 GENERAL 仍需按 §4 盘点，不能让接送旧行同时变成两个方向可约。 |
| `schedule_reservation` | 单一 start/end、pickup_start/return_start、TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED；无选窗 ID | 接送两方向的原选窗、完整起止及占用关系由 SCH 自有 claim 事实承担；主预约状态变化和两 claim 同事务。现有 `order_id NOT NULL` 与 hold→order 创建时序缺口不在本补充改列解决。 |
| `staff_service_capability` | staff/service 唯一、状态仅 ENABLED；无集合版本 | 保留为授权明细。移除即差集删除明细并留审计；不造 DISABLED 枚举。集合版本在独立头记录。 |
| `staff_availability_window` | staff/store/AVAILABLE-CLOSED/区间/version | 本轮无新的业务状态；减少可用性写入遵守共同 guard 和权威预约/指派保护。 |
| `order_staff_assignment`、`pet_order.service_staff_id`、`merchant_staff` | 各在 ORDER/MER 所有权内 | SCH 不加物理跨模块 FK、不直读这些表；经 ORDER/MER 公共 API 获权威当前事实。 |

所有排期时段为分钟级半开 `[start_at,end_at)`，相邻可接续；外部带偏移时间到库中 UTC `DATETIME(3)` 解释依 23 号执行。Snowflake 业务 ID 在库内 BIGINT、跨模块/HTTP 为十进制 String。数据库逻辑设计不授权将双方向变成固定 60 分钟槽。

## 2. SCHC-1：每店稳定 guard 逻辑记录

SCH 自有 `schedule_store_capacity_guard`：`store_id BIGINT` 为稳定唯一身份和主键，`version BIGINT`、`updated_at DATETIME(3)` 用于诊断/演进，不把版本当 Redis 租约或可在事务外持有的 token。门店首次涉及容量写入时以唯一约束原子建行/取行锁；即使当下没有开放窗或预约，该稳定行也必须存在或能并发安全创建。`SELECT ... FOR UPDATE` 查无窗口/预约行不能替代它。真实写方在同一主库 DataSource、同一顶层事务中按数值 storeId 升序锁 guard，直至提交/回滚；跨域事实经公共 API 同事务当前读。未来物理建表、插入语句/锁行为、隔离级别和死锁恢复须在 MySQL 多连接测试中证明，不能仅凭本逻辑说明完成。

一个门店 guard 串行其容量相关写入，但**不计算**可用人员，也不赋予 SCH 读取 ORDER/MER 持久层的权力。ORDER 必须能按受影响 `staff_id/store_id` 完整找出仍需保护的当前指派，包含预约已 RELEASED、没有活跃 claim 的订单；只从活跃 claim 的 `reservation_id` 反查会漏保护对象。该查询/订单状态不变量及所需 ORDER 索引尚待后续合同与 DDL 验证，缺席时减员失败关闭。跨服务共享员工的完整可行性证明/是否有技术占位表未批，不能因 guard 已设计就放行减员或接送 hold。

## 3. SCHC-2：能力集合头与原子 CAS

SCH 自有 `staff_capability_set` 逻辑记录：`staff_id BIGINT` 唯一主键，`store_id BIGINT` 固定归属，`version BIGINT NOT NULL` 为非负单调版本，`updated_at DATETIME(3)`。经 MER 权威事实确认员工合法但头与明细都不存在时，GET 逻辑返回空集合与 `version:"0"`，**不因 GET 写入头**；读取 MER/DB 失败时不能合成空集合。第一次不同 requestId 的 PUT 带 `expectedVersion:"0"`，同事务以唯一键建立头并推进到 1；并发 loser 返回版本冲突。已建立头的更新以 `version=expectedVersion` CAS、明细替换、append-only 动作审计及首次幂等回执同事务完成；相同 requestId 同参数重放无第二次递增。新 requestId 即使新旧集合相同，成功 PUT 仍递增并记录动作。全量移空明细后头保留，不发生版本 ABA；版本上溢拒绝并告警。

迁移前须盘点所有已有 `staff_service_capability` 明细与集合头的组合。**明细非空、头缺失**是 `LEGACY_UNVERSIONED`，GET/PUT 503 并隔离，绝不能当首次空集合/version 0 或由 PUT 全量覆盖。SCH 核对明细状态、staff/store 归属与重复/悬挂事实后，给可恢复集合回填头和有记录的非零基线版本（建议从 1 起）；异常 ID 单列并持续阻断。头 version 0 却有非空明细也视为不一致。回填需避免并行 PUT/读写窗口造成 ABA，验证回填前后每名员工的服务 ID 集合完全一致；实际批量脚本和上线锁定步骤仍未交付。

版本在 Java 为 `long`、DB 为 BIGINT，在 HTTP/JSON 为**十进制 String**；不能经 JavaScript Number 损失 `9007199254740993` 等值。`staff_service_capability` 的既有 `(staff_id,service_id)` 唯一约束继续防明细重复。集合输入具体 `serviceId` 去重要求为**重复即拒绝**，类别不能隐式批量赋权；物理排序/索引方案尚未落为 DDL。移除能力的 reason 必填并同业务审计，减少可用性必须再经 guard 与占用保护；没有事实/证明时失败关闭。没有每人 200 项的业务上限。

能力维护只要求员工在职 ACTIVE、目标归属和商家/门店工作台准入等已批写入门禁；`service_enabled=0` 的员工可以由获权主账号编辑能力明细。容量查询另按在职**且在岗**与排班/能力取交，不因可编辑就把停排员工计入可约人数。

## 4. SCHC-3：窗口 kind 与两条权威 claim

`schedule_availability_window.window_kind` 逻辑上为非空 `GENERAL/PICKUP/RETURN`。到店服务仅 GENERAL、接送服务仅 PICKUP/RETURN；新建必须由命令显式给 kind 并校验履约类型。旧行即使物理迁移采用 GENERAL 默认/回填，也不自动获得接送两个方向的可约资格。窗口 `merchant_id/store_id/service_id/window_kind` 是身份，PUT 不得通过换目标或 kind 绕过旧占用；关闭/重开保留原 ID 和历史。建窗/重开相交检查使用同店 guard，不能依赖空范围行锁。

SCH 自有 `schedule_reservation_claim` 逻辑记录至少保存 `claim_id BIGINT,reservation_id BIGINT,window_id BIGINT,store_id BIGINT,service_id BIGINT,kind,start_at DATETIME(3),end_at DATETIME(3)`。接送在每个 `reservation_id` 下各有一条 PICKUP/RETURN，`window_id` 指向**原所选**窗口，时间快照是其被选择时的**完整** `[window.start,window.end)`；例如 PICKUP 10:00–11:00、RETURN 12:00–13:00 各占完整一段。两个窗口必须同店同服务、分别为 OPEN PICKUP/RETURN，原 `pickup_start_at/return_start_at` 与相应窗 start 一致，且返程开始至少晚 120 分钟。结束值从 SCH 权威窗取，不能从单一 reservation 主区间、固定时长或服务 duration 推断。

到店 GENERAL claim 同样不能缺 `window_id`，但现有到店 hold 只有 `appointmentStart/appointmentEnd`。建议 SCH-003 在 guard 内用店/服务与**完整**预约区间唯一关联一条 OPEN GENERAL 窗并快照原 ID；相交多窗、跨窗或无唯一覆盖时不能取第一条/默认造窗。是否需 `selectedGeneralWindowId`、跨窗是否形成多条 GENERAL claim 以及相应唯一键，须按既有到店时长/前端选择行为再冻结；本次接送双 ID 批准不定义其产品规则。无权威唯一关联时受影响写入失败关闭。

接送两个 claim 与父预约在一个事务内创建/交换/释放；对 TEMP_LOCKED/CONFIRMED 计占用，只有**已提交** RELEASED/EXPIRED 才解除。claim 的有效状态由父预约权威状态投影，不另设可先于父预约独立提交的“已释放”状态。原窗口 close、降容量、改时间时必须按原 `window_id` 和旧区间检查，不能因为新 kind/新时间不重叠而遗失旧占用。窗口升容量、身份不可变、审计与 CAS 仍按已批写入规则。接送 `reservation_id+kind` 唯一约束、GENERAL 的未决基数/唯一键、窗口/时间查询索引、跨天匹配和一致性检测都是**物理 DDL 待完成项**；本文件不提供可执行索引语句或声称 DB 已自动保证“两条且仅两条”。

既有 `schedule_reservation.order_id NOT NULL` 与 10 号先 hold 后 create 的顺序仍需 SCH-003/TX-001 明确预生成/绑定方式；claim 不持久化一个自造的 NULL 订单关联来绕过问题。ORDER 指派生命期、双 claim 最终是否同一人、跨服务人员可行性算法也未冻结。

## 5. SCHC-4：存量 GENERAL 分类、迁移和回滚门禁

上线前产出不可变盘点快照，至少按 `store_id,service_id,fulfillment_type,window_status,reservation_status` 分类，记录旧 GENERAL 的 ID、相交预约和原始时间。IN_STORE 的 GENERAL 在核对订单主区间/选窗映射后可保留；PICKUP_DELIVERY 旧 GENERAL 不自动复制为两条 OPEN 方向窗：只有一个旧窗和 `pickup_start_at/return_start_at` 两个开始值，无法证明原两段结束/窗口身份。接送旧行从新预约可约投影隔离，但保留已锁定/已确认订单的历史履约与写入保护。可由可信历史事实恢复完整两方向 claim 的才回填；无法恢复的列明 ID 和原因，阻断相关接送发布、旧窗改写及减员，进入逐单处置。不得自动取消订单、伪造双向占用或丢弃历史。

真实迁移交接须给出盘点总数/分组数、回填前后校验、并行写入/回滚兼容、失败告警和旧版本读写保护；生产开关在未解决数据与真实 MySQL 验证前关闭。本文没有迁移版本号、可执行脚本或已完成索引 DDL，不把仅加 `DEFAULT GENERAL` 当完成存量语义迁移。

## 6. 审计、幂等和未完成工作

SCH 写动作保留独立 append-only 审计：目标类型/ID、merchant/store、动作、操作者、时间、requestId、版本前后及必填原因与业务同事务；它不以公共幂等绑定取代。首次成功回执、业务行、claim、集合头/明细与审计同顶层提交，失败不留下伪成功审计。批量部分成功只对真实业务受阻返回 `blockedWindows`，事实源/DB 故障整笔回滚。具体审计表字段/唯一键和物理迁移需后续脚本核验。

仍待 SCH-003/ORDER 独立 CCR：跨服务共享员工的完整保护证明/技术占位算法和性能界限、接送两 claim 与一名最终指派员工的关系、ORDER 未完成指派的精确状态集合、hold/order 绑定时序。现有 06/07/10/11 及代码尚未具备本文件的行和接口；[测试映射](../../planning/issues/wave-3/SCH-004-contract/REVIEW-TEST-MAP.md)全部 **NOT_EXECUTED**。实现须按 34 号 API 补充和各 Owner 后续合同交付，不得以本存储设计批准替代测试或生产启用。

上述未决项还包括 ORDER 按员工/门店查全当前保护指派的完整性协议，以及到店 GENERAL 的唯一选窗关联、跨窗基数和是否增加 `selectedGeneralWindowId`。在这些契约与索引/迁移验证缺失时，相关写入继续失败关闭；PR81 仍是合同规范草案，不能标 SCH-004 或 SCH-003 为 DONE。
