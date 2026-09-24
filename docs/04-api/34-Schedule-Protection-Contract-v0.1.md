# 排期写入保护与双向选窗契约补充 v0.1

状态：**ACCEPTED_CONTRACT_NOT_IMPLEMENTED**（仅 SCHC-1～4 已批逻辑）；2026-09-24。批准来源：[四项技术裁决回执](../../planning/ccr/CCR-W2-API-001/schedule-write-completion-decisions.md)。本补充以 SSOT §12/§13/§29、最终 C/商家 PRD、[排期写入 v0.2](../../planning/ccr/CCR-W2-API-001/schedule-write-proposal.md)为前提。它不是已装配的 Java 接口、已开放 HTTP 路由、OpenAPI 实现标签或生产 Schema。07/10/11/12 号将在后续实现切片由唯一 Writer 正式同步；当前已运行接口以其现状为准。

## 1. 当前供给与交付闭环

当前 `GET /api/v1/c/services/{serviceId}/availability`（OpenAPI `cGetServiceAvailability`）每个 `items` **只有** `start,end,effectiveCapacity,occupiedCount,remainingCapacity,available` 六字段；不返回 `windowId` 或 `kind`，不接受 `kind` 查询参数。`CScheduleController.window` 和 10 号 §3.4 均按该六字段输出，07 号 §6.1.1 仍按单一窗口集描述。SCH-001/002 的展示响应不能让 C 端产生本补充所需的 `selectedPickupWindowId/selectedReturnWindowId`；本补充批准后也**不立即改变**该响应。

后续 SCH-004（窗口/kind 写入与读投影）和 SCH-003（hold/swap 及 claim）必须联合交付如下闭环，并同时更新 07/10/11、客户端契约测试与路由实现，才可让 C 端接送选择启用：

| 后续目标契约，当前尚不可调用 | 必要字段/守卫 |
|---|---|
| C 端可约 GET 保持原路由 | 保留原六字段语义；每项**增列** `windowId`（正 Long 十进制 String）和 `kind`（`GENERAL/PICKUP/RETURN`）；可选 `kind` 过滤与服务履约方式对应。IN_STORE 只给 GENERAL，PICKUP_DELIVERY 分别取 PICKUP/RETURN。窗口 ID 为供选的 SCH 权威 ID，不是客户端拼接的时间键；不暴露配置容量或版本等商家维护细节。当前接口遇未知 `kind` 参数仍按现行 400，直到正式增量发布。 |
| 接送创建订单与改期请求传到 `ScheduleCommandApi.hold/swap` | 对 PICKUP_DELIVERY 增 `selectedPickupWindowId` 和 `selectedReturnWindowId`（跨模块/HTTP 均 String）并保留原 `pickupStart/returnStart`；两 ID 缺失、重复、归属/服务/kind 错误或与所报开始不一致均拒绝，不以六字段旧响应猜测 ID。IN_STORE 不填这两个方向 ID，既有到店时段语义不改。最终请求形状由 07/10/11 同步。 |
| SCH-003 权威复核 | 同一门店闸门事务内按两 ID 当前读 OPEN 窗，确认同店同服务、方向分别为 PICKUP/RETURN、`pickupStart == PICKUP.startAt`、`returnStart == RETURN.startAt`，校验 `returnStart >= pickupStart+120分钟`，再用**各自完整窗口** `[startAt,endAt)` 形成两条 claim 并原子锁定。展示 GET 不构成租约；窗/容量变化时服务端拒绝并让用户重选。 |

最终 C 端 PRD §5.1 写“用户必须选择一个上门时段，作为接宠时间窗口”；例子为上门 10:00–11:00，最早送回 12:00–13:00。商家 PRD §5.4 要求接送分别占用上门、送回预约时段。整窗占用沿用该产品选择行为；没有批准在一个商家窗口内任意截取子区间、固定 60 分钟或用服务项目 duration 推算两段时长。到店 GENERAL 继续按服务时长/原预约区间合同处理。

## 2. SCHC-1：共同锁与权威事实

SCH 拥有每 `storeId` 一条稳定的 `schedule_store_capacity_guard`。所有可能改变预约、窗口容量、人员可用性或当前指派的写路径，在**同一个主库 DataSource、同一个顶层本地事务**中，经 SCH 公共 API 先取得该行排他锁，并持有到提交/回滚。首次无行用唯一键原子建行再锁；锁空的窗口查询结果不等于已锁门店。多个门店按数值 ID 升序拿锁，这是通用协议；本补充不增加跨店改期能力。

参与者至少是 SCH hold/confirm/swap/release/expire、SCH-004 窗口受保护修改与排班/能力减员、ORDER 指派/改派/撤派、MER 停用或其他减少员工服务资格的命令。MER disable 的 27 号 `IMPLEMENTATION_BLOCKED` 门禁继续有效，直到 ORDER 与 MER 真实接入。`biz` 只通过另一域 `api` 协作，不跨域访问 Repository/Mapper/DO/Entity。

事务顺序：23 号 Admission 独立绑定请求意图；Execution 锁幂等记录、重验权限；按升序锁 SCH guard；取得 SCH 窗口/claim 当前事实；经 ORDER API 取当前指派和未完成订单事实、经 MER API 取当前员工状态及版本、经 SERVICE API 取目标服务归属；完成守卫后各 Owner 只写自己的事实，与审计及首次成功回执同顶层提交。跨模块调用必须加入相同事务并在锁后做主库**当前读**；独立 `REQUIRES_NEW` 只读、RR 旧快照、缓存、另一 DataSource 或一次远程查询不能证明锁内事实。不能满足这一前提的写路径保持关闭并另走协调契约。

07 号后续同步的最小公共能力如下；**方法名是建议签名，事实字段和事务语义是本次批准范围**：

| Owner 能力 | 最小请求与回执 |
|---|---|
| SCH `ScheduleCapacityGuardApi.acquire` | `storeIds: List<String>`；仅在已存在的顶层业务事务中调用，数值排序去重，返回不释放锁。另提供同事务 SCH claim 当前事实：`claimId,reservationId,orderId?,storeId,serviceId,windowId,kind,startAt,endAt,reservationStatus,version` 和查询完整性；未知/缺失不能合成空列表。 |
| ORDER 按预约当前指派事实 API | 按受影响活跃 claim 的 `reservationIds` 返回可区分“尚未绑定订单/已绑定但未指派/已指派”的权威状态，以及 `orderId?,currentStaffId?,protectRequired,orderVersion?,assignmentVersion?`；`pet_order.service_staff_id` 与 `order_staff_assignment.is_current` 不一致时报依赖故障。此查询**不构成全部当前指派的完整性证明**。 |
| ORDER 按员工/门店的保护指派完整性 API | 减少某员工排班/能力时按 `storeId,affectedStaffIds` 查询；影响整店人员容量时按 `storeId` 覆盖全店。返回所有仍需保护的当前指派，**不能仅由活跃 claim 反查**；每项至少含 `orderId,reservationId?,staffId,storeId,protectRequired,orderVersion,assignmentVersion` 与可验证的完整结果/故障区分。与上一行按预约事实对账；无活跃 claim 却仍需保护的指派不能遗漏。具体方法名、ORDER 生命周期状态集合和性能方案由 ORDER 后续合同冻结。 |
| MER 员工当前事实 API | 按 `storeId,staffIds` 完整返回各员工 `staffId,storeId,employmentStatus,serviceEnabled,version`；不存在、明确不合格、未知/读取故障要区分。SCH 自己计算具体服务能力和 AVAILABLE 排班，不让 MER 访问 SCH 表或按总人数推预约容量。 |

任何读取失败、未知枚举、ORDER 当前指派不一致、缺 claim、**按员工/门店的完整性查询缺席**或无法证明跨服务共享人员仍可履约，均 503 `COMMON_DEPENDENCY_UNAVAILABLE` 并整笔回滚；不能默认为“0 个预约/0 个指派”，也不能当批量关窗的普通 `blockedWindows`。例如 reservation 已 RELEASED，但 ORDER 仍有按其权威状态需保护的当前指派，减员不能因活跃 claim 清单为空而放行；该订单应被保护还是先对账由 ORDER 待冻结的生命周期规则决定，规则/事实缺失时失败关闭。已知被 TEMP_LOCKED/CONFIRMED 或当前指派阻挡的目标按已批 409 业务冲突处理。TEMP_LOCKED 即使过了 `lock_expire_at`，只要未提交 EXPIRED/RELEASED 状态仍保护。批量部分关闭仅分业务受阻和可关闭目标；基础设施故障整笔回滚。

同请求键/参数重放、异参冲突、授权重验、死锁/连接失败回滚与 commit ACK 未知查原键均按 23 号，不用新键重做。并发例：T1 缩短 X 排班与 T2 在相交时段 hold 同店，先拿 guard 者提交后，后者在锁内看其新事实并拒绝；不能两者都按旧快照成功。**共同锁不代替容量证明算法**，跨服务共享员工的完整证明方法仍见 §6。

## 3. SCHC-2：能力集合 GET/PUT 与首次空集合 CAS

能力按具体 `serviceId` 授权，类目只供页面分组。建议 GET/PUT `/api/v1/merchant/staff/{staffId}/service-capabilities`；这是**未来契约**，当前 10/11 号尚未提供这些已实现路由。MINIAPP 会话取可信主体，V1 商家主账号 OWNER 经后端动作权、merchant/store/staff 归属、员工在职 `employment_status=ACTIVE` 与商家/门店工作台准入分别校验；不能由请求体声明自己是 OWNER。**员工暂不在岗 `service_enabled=0` 不构成编辑能力的额外门禁**，但该员工在容量查询中仍不计为可用人员（SSOT §29/SCH-002）。无权与不存在按既有防枚举/权限规则，事实不可读 503。GET 成功含 `staffId,merchantId,storeId,serviceIds,version`；PUT 请求含 `merchantId,storeId,serviceIds,expectedVersion,reason?`，移除任一现有能力时 reason 必填且保存到同事务审计。所有写入携带 UUID `X-Request-Id`。

`version/expectedVersion`：数据库 `BIGINT`、Java 非负 `long`、HTTP/JSON 非负 Long 十进制 **String**，不经 JS Number；`9007199254740993` 必须精确往返并用于 CAS。GET **仅在已确认合法且集合头和能力明细都不存在**时返回 `serviceIds:[]、version:"0"`；这不是错误时的默认空集合。若 `staff_service_capability` 已有明细却无集合头，属于未迁移存量，GET/PUT 都返回 503 并隔离该员工，不能展示空集合、首次 CAS 为 0 或覆盖旧授权。上线前由 SCH 盘点这些 staff，核对 MER 归属/能力明细并回填持久集合头与可解释的非零基线版本；异常行另列清单。头为 0 却有非空明细也视为不一致并失败关闭。首次合法 PUT 必带 `expectedVersion:"0"`，唯一键原子建立头并推进到 `"1"`；两个读到 `"0"` 的不同编辑仅一个成功，另一 409 `COMMON_CONFLICT`，提示重读。后续在同事务 `version=expectedVersion` CAS、替换能力明细、写 append-only 动作审计和首次幂等回执；0 行即冲突，不能 last-write-wins。集合变空仍保留头和单调版本，不恢复为 0；版本上溢拒绝并告警。

PUT 是**全量替换具体服务 ID 集合**：重复 ID 拒绝 400，跨店/非目标服务拒绝，不能按类别自动扩权。移除用明细差集删除及审计；现有 `staff_service_capability.status` 只有 ENABLED，**不引入未批准的 DISABLED 枚举**。不同 requestId 但同内容的新成功 PUT 仍推进版本并留审计；相同 requestId、相同规范参数重放返回第一次成功版本，不再重写；同 key 异参 409 `IDEMPOTENCY_KEY_CONFLICT`，授权每次重验。`serviceIds` 若作为无序集合参与 23 号参数规范，须按固定数值 ID 顺序规范化并拒绝重复，不能把请求键当版本。能力撤销在 §2 所需容量证明未到位时失败关闭；只增加能力也不跳过 CAS、鉴权、归属或审计。未批准“每人最多 200 项服务”上限。

## 4. SCHC-3：完整所选窗的 claim 与旧窗保护

到店服务仅用 GENERAL，接送仅用 PICKUP/RETURN。每个接送预约的两个 selected windowId 指向原 OPEN 窗，形成两条按 `reservationId+kind` 唯一的权威 claim；各 claim 保存原 `windowId,storeId,serviceId,kind,startAt,endAt`，时间为完整所选窗的 `[startAt,endAt)`。两 claim 与父 `schedule_reservation` 的新增/状态变化同事务，不允许只建立 PICKUP 或 RETURN 的半预约。`TEMP_LOCKED/CONFIRMED` 计占用；只有已提交 `RELEASED/EXPIRED` 才解除。SCH-001 的旧单区间重叠计数不能冒充接送两方向的权威计数。

**到店 GENERAL 关联仍有技术缺口**：现有到店 hold 只有 `appointmentStart/appointmentEnd`，没有 GENERAL 选窗 ID；本次仅批准接送两个 selected windowId，没有把到店关联一并视为解决。SCH-003 推荐在共同 guard 内按已选 `storeId/serviceId` 与完整预约 `[appointmentStart,appointmentEnd)` 当前读，只有**唯一一条**同店同服务 OPEN GENERAL 窗完整覆盖该区间，才可把该窗 ID 记入 GENERAL claim；不能取“第一条”、凭开始点命中就选、默认造窗或把歧义当无占用。若跨相邻窗或多个窗均可能关联，是否补 `selectedGeneralWindowId`、如何表示跨窗 claim，必须按既有到店服务时长和 C 端选择行为由 SCH-003 进一步核对后冻结；在此之前对歧义/跨窗情形失败关闭，不擅改到店产品规则。

窗 close、降容量、移时间或改变服务/kind 的守卫以**旧窗口 ID 与旧时间**及新候选事实共同检验，不能先改变 identity 再查“新窗无占用”。`storeId/serviceId/kind` 建窗后固定；要换目标或 kind，应在原窗合法关闭后另建新窗，且有占用时原窗不得关。已占用窗升容量仍经权限、版本、审计等已批守卫。同店/同服务/同 kind 的 OPEN 窗不得重叠，相邻半开区间可衔接。没有固定 60 分钟槽。

## 5. SCHC-4：旧 GENERAL 分组与上线门禁

启用分方向预约前按店/服务/履约方式、窗口状态与有效预约逐项盘点旧 GENERAL。IN_STORE 的 GENERAL 可在核验现有订单主区间与选窗关系后保留；PICKUP_DELIVERY 的旧 GENERAL **不得复制**成两条 OPEN PICKUP/RETURN，因为它缺方向和第二段结束，复制会凭空扩大可约容量。接送旧 GENERAL 从**新预约可约投影**隔离，已有 TEMP_LOCKED/CONFIRMED 继续保留原履约与保护；能用可信历史事实还原完整双 claim 的才迁移。不能恢复的记录列 ID、原因和受影响门店/服务，阻断相关接送发布/减员，逐单处置并验证回滚；不得自动取消、改变旧订单或假定旧预约已经释放。商家再按真实营业安排创建两组新窗。

## 6. 明确未冻结的依赖与交付状态

- **SCH-003/ORDER 后续独立 CCR**：跨服务共享员工的完整容量可行性证明或技术占位算法；已指派员工与两 claim 是否必须同人；性能与资源预算。逐服务人数减占用、逐分钟独立匹配或贪心不能代替证明。证明缺席时，受影响减员与接送 hold 失败关闭，这是 SSOT §29 已批执行边界。
- **ORDER/TX 绑定和完整性细节**：10 号流程先 hold 后 create，06 号 `schedule_reservation.order_id NOT NULL`；预生成 ID、绑定时序和“未绑定 hold”事实表达尚未定。ORDER 当前指派需保护的完整生命周期状态集合、按员工/门店完整查询的物理实现与索引亦未定；缺失时不得凭按活跃预约的查询放行减员。本补充不通过放宽 NOT NULL、清空指派或猜订单状态解决。
- **到店 GENERAL 技术关联**：同店/服务/完整预约区间在锁内唯一 GENERAL 窗可先作为推荐关联；跨窗、歧义、显式 `selectedGeneralWindowId` 的必要性和 GENERAL claim 数量仍需 SCH-003/客户端契约核对。接送双 ID 的批准不自动解决到店路径。
- **真实实现**：本文件不改现有 07/10/11/OpenAPI 字段、路由或状态标签；不实施 Java、迁移、开关或生产数据回填。正式同步需 SCH-004/SCH-003/ORDER/MER 联合审查、[存储补充](../03-database/34-Schedule-Protection-Storage-v0.1.md)的逻辑映射、[测试映射](../../planning/issues/wave-3/SCH-004-contract/REVIEW-TEST-MAP.md)的 MySQL 并发与大版本/接送选窗验证。全部业务测试当前 **NOT_EXECUTED**。

## 7. ROC-1～6 后续批准补记（2026-09-24）

本文件 §6 记录的是 SCHC-1～4 批准时尚未冻结的依赖；用户随后已批准[36号 SCH-003/ORDER 联合契约](36-Reservation-Order-Protection-Contract-v0.1.md)的六项推荐，批准回执见[ROC-1～6](../../planning/ccr/CCR-W2-API-001/reservation-order-protection-decisions.md)。跨服务完整可行性、接送最终同人、到店单 GENERAL 原窗、非空 orderId 的 hold/create 同事务、ORDER 全店当前指派完整性及改期保留指派失败留旧的**逻辑合同已冻结**，以 36 号覆盖 §6 中相应“未定”描述。此补记不代表 06/07/10/11/12、Java、DDL、迁移、索引或真实测试已完成；本文件 SCHC-1～4 及旧 GENERAL 隔离、缺证明失败关闭继续有效。状态仍是 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED`。
