# SCH-001 准备阶段 — 可预约事实盘点与范围核验（S1～S4）

日期：2026-09-23。分支 `codex/sch001-prep-20260923`（基于 develop `639b61a`）。角色：排期域后端（Backend Core，SCH 规范阶段）。
本切片为**准备/规范阶段文档**：不实现代码、不修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码与共享台账；获人工批准后由 Owner 同步权威契约再派发实现。

配套文档：CCR 草案 [schedule-availability-proposal.md v0.1（SCH-D1～D11，DRAFT 未获批）](../../../ccr/CCR-W2-API-001/schedule-availability-proposal.md)、[TEST-PLAN.md](TEST-PLAN.md)。

引用约定：PRD 为 docx，引用按章节号（提取自 word/document.xml 纯文本，行号为提取文本行号，仅供复核定位）；仓库内文档与代码引用为 文件:行号。

---

## 0. 范围登记核验（本切片从哪里来）

| 登记点 | 原文 | 位置 |
|---|---|---|
| 依赖图 | `SVC-001 → SCH-001 → SCH-002 → SCH-003 → … → TX-001`；`SCH3 --> ORD`；`SVC --> ORD` | planning/DEPENDENCY_GRAPH.md:19-26 |
| Issue Catalog | SCH-001「分钟级 Availability Query」P0，依赖 SVC-001，AllowedModules `backend/pet-schedule-*`，Tests `ORD-006,ORD-007`，Wave 3，BLOCKED（完整 Issue 门禁） | planning/ISSUE_CATALOG.csv:12 |
| Issue Catalog（下游） | SCH-002 `effectiveCapacity=min(configured,availableStaff)` 依赖 SCH-001 且 AllowedModules 含 `backend/pet-merchant-api`；SCH-003 预约生命周期依赖 SCH-002+PLAT-004；TX-001 依赖 `USR-001,SVC-001,SCH-003,PLAT-002` | planning/ISSUE_CATALOG.csv:13-15 |
| Wave2 附加门禁 | C003 真实查询 → `MER+SVC+SCH001/002`；M002 真实范围 → `AUTH+MER+SVC+SCH及签约` | planning/DEPENDENCY_GRAPH.md:54 |
| READY_QUEUE 更新 | 「下一段依赖链：核对可预约事实与契约缺口→SCH-001→SCH-002→SCH-003→TX-001」 | planning/READY_QUEUE_WAVE_2.md:3 |
| CCR 索引「可用性/排期管理」行 | 「对既有可用时间契约补页面所需未定义内容；原 Wave3 依赖不强拉到本波」，规范责任 Issue= SCH-001/002 | planning/ccr/CCR-W2-API-001.md:15 |
| 2026-09-23 收官 | 「完整『发布服务→消费者看到→预约』闭环仍不含预约（排期/订单域不在本轮范围）」；B5 售罄口径未决 | planning/progress/2026-09-23/ROUND-CLOSEOUT.md:47,59 |

**结论**：SCH-001 归属清晰——C 端可预约时间查询（HTTP10 §3.4 路由 + 07 号 §6.1 ScheduleQueryApi 的读侧），落在 `backend/pet-schedule-*`（两模块现为纯 package-info 空壳，见 §S1.8）。本切片不实现订单/支付/hold（TX-001/SCH-003 归属），不跳到 SCH-002 容量人员联动。

---

## S1. 事实盘点（可预约事实逐项：已有权威事实 / 缺事实源 / 缺契约）

排期硬规则基线（SSOT §一/§十二/§十三/§十九，AGENTS.md）：分钟级排期不固定 60 分钟槽；容量=`min(商家配置容量, 当前可用服务人员数量)`；服务人员由商家指派、用户不可选；单次服务必须预约；接送返程开始 ≥ 上门开始 + 120 分钟；改期最多一次。以下逐项核对事实源。

### S1.1 服务时长（duration_minutes）— 已有权威事实+读写两侧已交付

- Schema：`service_item.duration_minutes INT NOT NULL COMMENT '单次服务时长，用于分钟级排期'`（docs/03-database/06-核心数据库Schema-v0.1.sql:136）。
- 读侧已交付：`ServiceSnapshotDTO` 11 字段含 `durationMinutes`（07 号 §5.1.1）；HTTP 详情/列表已暴露 `durationMinutes`（10 号 §3.3.1）。
- 写入方已交付（2026-09-23 PR68 合入）：`durationMinutes(1..10080)` 校验（10 号 §4.10.1）。
- **结论：SCH-001 直接消费 `ServiceQueryApi.getServiceSnapshot` 即可，无缺口。** 到店型按时长占区间（商家端 PRD §2 排期履约，提取文本 50 行）的"占区间"语义属 hold/订单侧，SCH-001 只需返回窗口本身。

### S1.2 履约方式（fulfillment_type）— 已有权威事实+读写两侧已交付，但双时段呈现缺契约

- Schema：`service_item.fulfillment_type VARCHAR(32) IN_STORE/PICKUP_DELIVERY`（06 号:138）。
- 商家端 PRD §5.4 履约模型定义（提取文本 546-549 行）：**上门接送型分别占用上门与送回时段（送回开始 ≥ 上门开始+120 分钟、可跨天）；到店型按时长占用区间**；"排期日历按服务分别渲染，禁止混用"。
- C 端 PRD §5.1.15（提取文本 1139-1170 行）：用户分别选择**上门时段**与**送回时段**，送回候选与上门联动置灰；跨天送回需提示过夜。
- **事实缺口（Schema 级，如实登记）**：`schedule_availability_window` 表**没有窗口类型列**（06 号:165-180 仅 merchant/store/service/start/end/configured_capacity/status/version）——无法区分"上门可约窗口"与"送回可约窗口"。PRD §5.4 字段表明确列出「上门时段/送回时段」两个条件必填字段（提取文本 604-610 行）。SCH-001 的呈现口径列为决策点 **SCH-D4**（推荐：本切片窗口不分 kind，两类候选共用同一窗口集，120 分钟/跨天约束不在查询侧；Schema `window_kind` 增补留给写入方切片一并裁决）。

### S1.3 门店可预约时段窗口（schedule_availability_window）— 表已定义，**当前无写入方**

- Schema 已定义（06 号:165-180）：`id/merchant_id/store_id/service_id/start_at/end_at DATETIME(3)/configured_capacity INT/status OPEN-CLOSED/version`；索引 `idx_schedule_service_time(store_id,service_id,start_at,end_at)`、`idx_schedule_status_time`。跨天窗口无约束（寄养/过夜连续多日，商家端 PRD §5.4 核心功能，提取文本 555 行）。
- **无写入方（与 SVC-D4 同型缺口，如实登记）**：
  - 商家侧：HTTP10 §4.8 排期管理四路由（`GET/POST/PUT/DELETE /api/v1/merchant/stores/{storeId}/availability-windows`，10 号:1006-1013）只有路由名+请求示例，**无语义/校验/错误/幂等/鉴权定义**；无任何控制器实现（backend/pet-boot 全量检索无 availability 路由）。
  - 运营侧：**明确不代商家维护排期**（运营端 PRD §3.3 边界「不代商家维护排期」，提取文本 68 行；§6.2.5「运营端对商家排期只读监管，不得代商家修改排期」，提取文本 1021 行）。
  - 入驻建档不建排期（27/29 号链路无排期事实）。
- **矛盾披露（T1）**：C 端 PRD §5.1.15 用户角色行写「已登录宠物主；**商家排期可由商家或运营维护**」（提取文本 1140 行），与运营端 PRD §3.3/§6.2.5 矛盾。按资料优先级（SSOT>最终PRD）SSOT 无运营维护排期条款；运营端 PRD 边界条款更具体且与 22 号运营权限补充（V1 单运营、获权直接发布，但"不代商家"边界不变）一致。**推荐按运营端 PRD：写入方仅商家**（决策点 SCH-D7 附带披露，不阻塞 SCH-001）。
- **结论：SCH-001 读侧以该表为唯一权威窗口事实源；无写入方按 SVC-D4 披露模式登记（SCH-D3），测试以 SQL 夹具播种。**

### S1.4 商家配置容量 — 事实源=窗口行内字段，无独立事实

- `schedule_availability_window.configured_capacity INT NOT NULL`（06 号:171）；商家端 PRD §5.4 字段表「商家配置容量 整数 ≥1 可按时段调整」（提取文本 612-614 行）；「已有订单占用的时段不得关闭或降容量」（提取文本 560 行，写入方语义）。
- **结论：随窗口行读取，无独立缺口。** `min(配置, 可用人员)` 的第二项属 SCH-002（见 S4.1）。

### S1.5 服务人员可用性 — 事实分散三层，**SCH-002 所需查询缺失**

按 SSOT §12.2/§十三：实际可预约容量 = `min(商家配置容量, 当前可用服务人员数)`；07 号 §6.2 容量校验用 `qualifiedAvailableStaffCount`。事实分层核对：

| 层 | 事实 | 表/查询现状 | 缺口 |
|---|---|---|---|
| 在职/可服务 | 员工档案与在岗状态 | `merchant_staff`（06 号:97-114：employment_status ACTIVE/INACTIVE、service_enabled）已建表；商家域员工六接口已批（27 号 §6.1），但 **disable 保持 IMPLEMENTATION_BLOCKED**（27 号:114） | 无"按门店列在职可服务员工清单/计数"内部查询（现有 `getStaff` 单查，backend/pet-merchant-api/.../query/MerchantQueryApi.java） |
| 时间可用 | 人员可用时间窗 | `staff_availability_window`（06 号:182-198：staff_id/store_id/start_at/end_at/status AVAILABLE-CLOSED）已建表，**无写入方**（HTTP10 §4.9 路由壳，10 号:1041-1044），无任何查询契约 | 表在 06 号「2. 分钟级排期」区块（SCH 域事实），读取归 schedule-biz 自有 Mapper（无跨模块问题）；写入方随排期写入切片（SCH-D7） |
| 服务资格 | 员工-服务能力 | `staff_service_capability`（06 号:149-158：staff_id/service_id/status ENABLED，uk(staff_id,service_id)）已建表 | 同上：SCH 域事实，**无写入方、无契约**（员工管理六接口不含能力维护，27 号 §6.1 明确"岗位/资质/擅长项扩展不是六接口已覆盖能力"） |
| 跨域组合 | qualifiedAvailableStaffCount | 07 号 §6.2 仅有语义名 | **缺 pet-merchant-api 内部查询**：SCH-002 需要商家域提供"门店在职可服务员工"事实（`merchant_staff` 归 MER 域 27 号存储映射，schedule-biz 不得跨模块读），能力∩时间窗在 SCH 域内组合。列 SCH-002 前置缺口（S4.1），SCH-001 不实现 |

**结论（SCH-001 阶段口径）**：Issue Catalog 已把 `min(configured,availableStaff)` 划给 SCH-002（ISSUE_CATALOG.csv:13）。**SCH-001 的 `effectiveCapacity` = `configured_capacity`（窗口行值）**，`occupiedCount` 恒为 0（预约写入方 SCH-003 未交付，见 S1.7），`available` = 窗口 OPEN 且未被过滤。此阶段语义锁定列为 **SCH-D6**（含"SCH-002 升级 min 公式时本响应字段语义随之升级"的显式披露），避免读侧降级猜测人员事实（失败关闭：无事实源≠可约满，也≠不可约——按已批配置容量呈现，升级点在 SCH-002 契约里再裁）。

### S1.6 临时停业 — 无独立事实载体，语义=窗口 CLOSED

- 商家端 PRD §5.4：「支持设置营业时间、休息日、临时停业和手动开放或关闭时段」（提取文本 553 行）；「临时停业或关闭时段时若存在已占用订单，系统拦截并提示先处理订单」（提取文本 571 行）。
- `merchant_store` **无营业时间/停业列**（06 号:83-95；27 号存储映射明确「店铺营业时间/简介/相册/公告等现有列缺口按原 MER/M/ADM Issue 补齐」，docs/03-database/27-Merchant-Domain-Storage-v0.1.md:54）。
- **结论**：V1 事实模型中"临时停业/休息日"没有独立载体，等价于**不开窗或把窗口置 CLOSED**；"拦截已占用订单"是写入方校验。SCH-001 读侧规则：`status='CLOSED'` 的窗口不出现在 items（推荐，SCH-D2 附则）；整店无 OPEN 窗口即查询返回空 items（200，不是 404）。门店冻结/下线/商家停用不经排期表表达，由 SVC-D1b 四条件可见性在入口处拦截（404）。

### S1.7 占用/锁定事实 — 表已定义，**生产者（SCH-003/订单链）未交付

- `schedule_reservation`（06 号:197-226）：order_id/store_id/service_id/fulfillment_type/start/end/pickup/return/status `TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED`/lock_token/lock_expire_at/capacity_snapshot/qualified_staff_count_snapshot；uk(order_id)、uk(lock_token)、idx(store_id,service_id,start,end,status)。
- 生命周期语义已在 PRD 封板：临时锁定 10 分钟（商家端 §5.4 业务规则+§6.2 状态机；C 端 §5.1.15；运营端 §3.4 SLA 行：支付超时 10 分钟）；商家端 §6.2 排期槽位状态机（提取文本 1799-1848 行）：可约→临时锁定→已占用→已完成，超时释放回可约，仅退款最终成功后释放，改期原子交换。
- **SCH-001 阶段**：无任何写入该表的路径 → `occupiedCount` 恒 0、`remainingCapacity=effectiveCapacity`。如实披露（SCH-D3 同条），不得用测试夹具冒充真实占用验收。

### S1.8 资格/可见性事实（入口门禁）— 已交付，SCH 直接消费

- `ServiceQueryApi.checkBookable`（backend/pet-service-api/.../query/ServiceQueryApi.java）：四条件合取 + reasonCodes（MERCHANT_DISABLED/STORE_DISABLED/MERCHANT_NOT_ACCEPTING_ORDERS/SERVICE_OFFLINE，可并列），供 ORD/SCH 使用——设计时即为 SCH 预留（SVC-001 交接第 17 行）。
- 实现语义先例：`ServiceQueryService.bookability` 对 `storeId` 与服务归属不一致同样走 notFound（backend/pet-service-biz/.../application/ServiceQueryService.java:70-72）——SCH-001 的 storeId 一致性语义沿用该先例（SCH-D9）。
- **结论：无缺口。** SCH-001 入口可见性=复用 SVC-D1b（不可见→404 SERVICE_NOT_FOUND 不区分原因，防探测），事实源故障→503 COMMON_DEPENDENCY_UNAVAILABLE 失败关闭。

### S1.9 pet-schedule-* 模块现状 — 纯空壳，pom 依赖已预留

- `pet-schedule-api`/`pet-schedule-biz` 全部为 package-info 占位（无任何 Java 类型）；biz pom 已依赖 pet-common/pet-event-api/pet-schedule-api/**pet-merchant-api**/**pet-service-api**/mybatis/redis/pet-task-core（backend/pet-schedule-biz/pom.xml:18-59）——与"消费 checkBookable+商家事实经 API、不跨模块"的架构要求一致，无需改 pom（实现阶段确认即可）。
- 架构守卫：pet-architecture-test 随 CI；biz 不依赖 biz、不跨模块 Repository（ARCH001~005）。

### S1.10 汇总表

| # | 可预约事实 | 权威事实源 | 写入方 | 内部查询契约 | HTTP 契约 | SCH-001 可用性 |
|---|---|---|---|---|---|---|
| F1 | 服务时长 | service_item.duration_minutes | 已交付（33 号链） | 已交付（getServiceSnapshot） | 已交付（§3.3.1） | 直接消费 |
| F2 | 履约方式 | service_item.fulfillment_type | 已交付 | 已交付 | 已交付 | 直接消费；双时段呈现缺契约（SCH-D4） |
| F3 | 可预约时段窗口 | schedule_availability_window | **缺失**（§4.8 壳） | **缺**（07 号 §6.1 AvailabilityQuery 未定义） | §3.4 骨架（字段/会话/边界未定） | 本切片定义读契约；SQL 种子（SCH-D3） |
| F4 | 商家配置容量 | 窗口行 configured_capacity | 同 F3 | 同 F3 | 同 F3 | 随窗口读取 |
| F5 | 可用服务人员数 | merchant_staff(MER)/staff_availability_window+staff_service_capability(SCH) | **缺失**（§4.9 壳） | **缺**（merchant-api 无门店在职可服务员工查询） | 无（用户不可选人） | 不在本切片（SCH-002 前置缺口，S4.1） |
| F6 | 临时停业 | 无独立载体=窗口 CLOSED | 同 F3 | 同 F3 | 同 F3 | CLOSED 不进 items（SCH-D2 附则） |
| F7 | 占用/锁定 | schedule_reservation | **缺失**（SCH-003） | 07 号 §6.2 命令壳 | — | occupiedCount 恒 0，如实披露（SCH-D3/D6） |
| F8 | 资格/可见性 | service_item+merchant/store 事实 | 已交付 | checkBookable 已交付 | §3.3.1 已交付 | 直接消费（404 沿用） |

---

## S2. SCH-001 范围裁剪（HTTP §3.4 字段完整性核对）

### S2.1 已定义（沿用，不改）

- 路由 `GET /api/v1/c/services/{serviceId}/availability`，Query=`storeId/startDate/endDate`，返回 `items[{start,end,effectiveCapacity,occupiedCount,remainingCapacity,available}]`（10 号:363-399）；内部调用 `ScheduleQueryApi.queryAvailability`。
- 规则四条（10 号:401-405）：分钟级时间区间；不暴露固定 60 分钟槽位概念；有效容量由服务端计算；C 端不能自行用前端人数计算容量。

### S2.2 未定义（核对结论，逐项列决策点）

1. **会话语义（SCH-D1）**：§3.4 未写会话语义。STR-D8 匿名四路由=`/c/stores`、`/c/stores/{storeId}`、`/c/stores/{storeId}/services`、`/c/services/{serviceId}`（10 号:290），**不含本路由**。现状代码：`CBearerSessionFilter.protectedPath` 前缀 `/api/v1/c/services/` 命中本路由→强制登录（backend/pet-boot/.../config/CBearerSessionFilter.java:62）；`optionalSessionPath` 仅四路由（同文件 71-77 行）。PRD：C 端 §5.1.13「所有用户浏览；已登录用户可进入预约」、§5.1.15 预约服务页用户角色=「已登录宠物主」。**推荐：维持登录态（无 token 401 COMMON_UNAUTHORIZED），不扩 STR-D8**；备选=随浏览族匿名（需人工批准扩第五路由，涉匿名防爬面）。读操作无 requestId 幂等要求（23 号 §3）。
2. **错误两分（SCH-D8）**：服务不可见（不存在/OFFLINE/DRAFT/REVIEWING/REJECTED/商家停用/门店停用/不接新单）→ 404 `SERVICE_NOT_FOUND` 沿用 SVC-D1b（不区分原因，防探测）；事实源故障/读取失败/状态未知 → 503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭（绝不降级为可约）；参数问题→400 `COMMON_INVALID_ARGUMENT`。**无新错误码**；SCHEDULE_* 8 码（12 号 §4）留给 hold/swap 命令侧（SCH-003），查询侧不引入。
3. **分页/日期范围边界（SCH-D2）**：§3.4 无分页信封（items 直接返回）——日期范围即边界。缺：startDate/endDate 格式（推荐 `yyyy-MM-dd` 日粒度，按平台业务时区 Asia/Shanghai（23 号 §2）解释为 `[当日00:00, 次日00:00)`）、必填性（推荐两者必填）、跨度上限（推荐 ≤31 天，防全表拉取）、endDate≥startDate、过去日期窗口过滤（推荐 `end_at<=now` 的窗口不返回；进行中窗口返回且 `available=false`）。跨天窗口（寄养/过夜）与查询区间部分相交时整体返回（不切割，推荐，见提案 §3）。
4. **时区（并入 SCH-D2）**：响应 items 沿用带偏移 ISO-8601（10 号 §2.6，先例 `2026-09-12T09:15:00+08:00`）；查询日期无偏移，按 Asia/Shanghai 解释；非零亚毫秒拒绝（23 号 §2）。
5. **提前预约窗口（SCH-D5）**：**核验结论=C 端/商家端/运营端 PRD 与 SSOT 均无"最短提前时长/最远可约天数"条款**（C 端 §5.1.15 仅「默认当天，支持切换」；运营端 §3.4 SLA 行只有支付超时 10 分钟/确认 30 分钟/锁定 10 分钟）。**推荐：V1 不引入最短提前预约时长限制**（无产品规则依据，不自行造规则）；仅按"过去窗口过滤"处理；最远可约上限由日期跨度上限（31 天）间接约束。若产品需要（如"至少提前 30 分钟"），请在裁决时给出规则值，SCH-003 hold 侧同步。
6. **storeId 一致性（SCH-D9）**：Query 携带 storeId 但语义未定义。推荐：必填；与服务归属门店不一致 → 404 `SERVICE_NOT_FOUND`（与不可见同响应，防探测；先例 ServiceQueryService.bookability:70-72）。
7. **items 状态呈现（SCH-D10）**：PRD §5.4 字段表「可用状态=可约/已锁定/已占满/已关闭，前后台共用」——§3.4 响应只有 `available` 布尔。推荐：**不新增 status 枚举字段**，`available=false` 统一表达已占满/不可约（不向 C 端暴露锁定细节，防探测/防依赖内部状态），商家端日历的细分状态由写入方切片另行定义。排序按 `start_at` 升序；无可约窗口=200 空 items（与 404 严格区分，先例：开放城市确证无可见门店 200 空页）。
8. **内部 API 形状（SCH-D6）**：07 号 §6.1 `AvailabilityQuery`/`ReservationQuery` **仅名称引用未定义形状**（§6.3 AvailabilityResult 单区间已定义）；本提案补 `AvailabilityQuery` 与窗口列表 DTO，`getReservation` 留 SCH-003。`QueryContext` 三字段不扩展（07 号 §2.2/§21）。
9. **OpenAPI（SCH-D8 附带）**：11 号现无 availability 操作（检索仅 cListStores/cGetStore/cListStoreServices/cGetService 等；9862/9891 行的 availability 是 AuthActionDefinition 字段名，无关）。获批后增补 `cGetServiceAvailability`（`ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步，MER-001/SVC-001 同款披露）。

---

## S3. 写入方缺口与拆分建议

### S3.1 谁维护可预约时段（PRD 证据）

- 商家端 PRD §5.4 排期/预约管理：页面角色=**商家管理员**（提取文本 544 行）；核心功能=日历展示+按分钟自定义时段+设置营业时间/休息日/临时停业+手动开关时段（551-556 行）；「V1.0 排期由商家直接手工维护分钟级可预约时段，不做周模板或按营业时间自动生成的循环排期」（556 行）。
- 角色面：商家端 §5.2 角色清单含「排期负责人」（提取文本 215 行）；10 号:1935 注记「排期负责人仅获授排期/人员时间配置」——写入方权限模型（主账号 vs 排期负责人）需 AUTH/RBAC 配合，属写入方切片范围。
- 运营端：只读监管（§3.3 边界+§6.2.5 门店排期监管：查可约/已锁定/已占用/已关闭分布、排期利用率、超时释放记录——**运营侧读路由也未定义**，登记为后续运营监管切片缺口，不扩入 SCH-001）。
- 矛盾 T1（C 端 PRD「可由商家或运营维护」）见 S1.3，推荐按运营端 PRD 仅商家。

### S3.2 推荐拆分：SCH-001 仅做"读 + SQL 种子"（复刻 SVC-D4 先例）

**推荐**：SCH-001 交付读侧（HTTP §3.4 + 07 号 §6.1 读形状 + schedule-biz 读实现 + boot 接线），窗口数据以 SQL 夹具播种；**写入方（§4.8 四路由命令+商家排期管理页+§4.9 人员可用时间+staff_service_capability 维护）另立切片承接**（SCH-004 候选编号或归 M-002 排期页切片，SCH-D7 待裁决）。理由：

1. **先例**：服务域"读先行（SVC-001）→写入方后补（ADM-001 service-write）"已走通两轮，SVC-D4 披露模式（SQL 播种+不声明完整 E2E）有 QA 三方印证先例。
2. **解阻塞**：依赖图上 SCH-002/003/TX-001 均以 SCH-001 为前置，且 SCH-001 是纯读（无幂等/无事件/无 Schema 变更），先行合并不产生回滚面；写入方涉及排期命令幂等（27 号 §7 执行序）、临时停业拦截（已占用订单不降容量）、`window_kind` Schema 增补、排期负责人 RBAC，复杂度独立成片。
3. **风险披露**：与 SVC-D4 同——"商家开窗→消费者查到可约时段"完整流程在写入方交付前保留未验收，SCH-001 测试报告不得声明完整预约 E2E（ORD-006/007 映射同样只报模块证据）。

### S3.3 临时停业/CLOSED 窗口语义（读侧口径）

- CLOSED 窗口**不返回**（items=可约窗口集合，SCH-D2 附则）；无窗口/全 CLOSED=200 空 items。
- 写入方语义（「已有订单占用的时段不得关闭或降容量」「临时停业存在已占用订单时拦截」）属写入命令校验，SCH-001 不实现不定义；`version` 列供写入方乐观锁（27 号 §7 同款），读侧不暴露。

---

## S4. SCH-002 / SCH-003 前瞻（只为契约不留硬伤，不展开）

### S4.1 SCH-002 前置缺口：人员可用性查询

`effectiveCapacity=min(configured,availableStaff)` 需要三类事实（S1.5）：merchant_staff（MER 域）、staff_availability_window + staff_service_capability（SCH 域 06 号排期区块）。**缺口**：pet-merchant-api 无"按门店列在职可服务员工（employment_status=ACTIVE ∧ service_enabled）"内部查询（现有五查询均不覆盖）。SCH-001 提案仅预留：SCH-002 时增补商家域第六查询（建议形状 `MerchantStaffQueryApi.listActiveStoreStaff(storeId)` 或计数形，27 号增补走 CCR），SCH 域内再与能力/时间窗求交得 `qualifiedAvailableStaffCount`。**本切片不实现、不定义该查询形状**（属 SCH-002 契约），只在 CCR 登记依赖，避免 SCH-002 时发现跨模块禁令硬伤。

### S4.2 SCH-003 生命周期与幂等/乐观锁先例

- `schedule_reservation` 表已含 lock_token/lock_expire_at/capacity_snapshot/qualified_staff_count_snapshot/version（06 号:197-226）；PRD 封板：临时锁定 10 分钟、超时自动释放、仅退款最终成功后释放（RefundSucceededEvent）、改期 swap 原子交换（新成功才释放旧）、refund 各阶段不释放（07 号 §6.2 规则已列）。
- 幂等/乐观锁先例=27 号 §7 执行序（独立短事务绑定 RESERVED→业务事务锁绑定/复核 expectedVersion→写事实+最小回执→同一次提交），稳定命令名进 23 号 scope；TEMP_LOCKED 生命周期任务化释放走 PLAT-004 durable AsyncTask（SCH-003 依赖 PLAT-004，Catalog:14）。SCH-001 的 HTTP 与 DTO 设计不与之冲突（读侧无 requestId 要求）。
- **B5 售罄口径**（ROUND-CLOSEOUT:47 未决）：售罄态无库存事实源、V1 容量属排期域动态事实。**建议在 SCH-002 启动前裁决**：推荐维持"不实现售罄态"，"无位可约"由 availability `available=false`/remainingCapacity=0 表达——这直接影响 SCH-002 的 effectiveCapacity 语义与商家端"可约/已占满"展示口径，SCH-001 的响应字段设计（布尔 available）已兼容该推荐。

### S4.3 对 TX-001 的衔接（仅登记，不实现）

TX-001 需要 SCH-003 的 hold 成功返回 reservationId（07 号 §6.2）；SCH-001 的 availability 仅展示，不构成预约授权租约（与 27 号 §4"一次快照不是预约授权租约"同语义），此边界在提案响应语义中明示，防 C 端把查询结果当锁。

---

## 9. 待人工裁决问题清单（汇总；详见 CCR 草案 SCH-D1～D11）

| # | 问题 | 推荐 | 影响面 |
|---|---|---|---|
| SCH-D1 | availability 会话语义：登录态 vs 扩 STR-D8 匿名第五路由 | 登录态 401（PRD"已登录可预约"）；匿名备选需扩裁决 | CBearerSessionFilter/CSessionSecurityConfiguration/OpenAPI security |
| SCH-D2 | 日期范围/边界：格式 yyyy-MM-dd、必填、跨度≤31 天、时区 Asia/Shanghai、过去窗口过滤、跨天窗口整体返回、CLOSED 不进 items、空结果 200 | 接受推荐组合 | 10 号 §3.4、实现校验、测试 |
| SCH-D3 | 无写入方披露与 SQL 种子口径（SVC-D4 复刻） | 接受（不声明完整 E2E；ORD-006/007 仅模块证据） | 测试报告口径、验收声明 |
| SCH-D4 | 上门接送型双时段呈现：窗口不分 kind（两类候选共用窗口集，120min/跨天不在查询侧） vs 06 号增补 window_kind 列 | 本切片不分 kind；Schema 增补留给写入方切片一并裁 | 06 号 Schema（未来）、C 端交互 |
| SCH-D5 | 提前预约窗口：PRD 无最短提前时长规则 | V1 不引入（不造规则）；过去窗口过滤为唯一时间门禁；若产品要最小提前量请给值 | §3.4 规则、SCH-003 hold 校验 |
| SCH-D6 | 内部 ScheduleQueryApi 形状（07 号 §6.1 细化）：AvailabilityQuery 五字段+窗口列表 DTO；SCH-001 阶段 effectiveCapacity=configured_capacity、occupiedCount 恒 0（SCH-002 升级 min） | 接受（阶段语义显式披露，防"读侧假装已算人员容量"） | 07 号 §6.1/§6.3 权威同步 |
| SCH-D7 | 排期写入方归属：SCH-004 候选 vs M-002 商家排期页切片（§4.8/§4.9 路由族+临时停业拦截+window_kind+排期负责人 RBAC+运营只读监管路由） | 另立切片（编号与 Owner 待派）；SCH-001 仅读+SQL 种子 | 依赖图登记、READY_QUEUE |
| SCH-D8 | 错误码复用：404 SERVICE_NOT_FOUND/503 COMMON_DEPENDENCY_UNAVAILABLE/400/401 全沿用，不新增；SCHEDULE_* 留命令侧；OpenAPI 增补 cGetServiceAvailability | 接受 | 12 号无变化、11 号增补 |
| SCH-D9 | storeId 一致性：必填，不匹配→404 SERVICE_NOT_FOUND（防探测，先例 checkBookable） | 接受 | §3.4、测试反例 |
| SCH-D10 | items 状态呈现：不新增 status 枚举，available=false 统一不可约；排序 start_at 升序 | 接受（商家端细分状态归写入方切片） | §3.4 响应、前端 |
| SCH-D11 | T1 矛盾（C 端 PRD"可由商家或运营维护排期" vs 运营端"不代商家维护"） | 按运营端 PRD：写入方仅商家；C 端该句建议勘误（不改权威 PRD，仅登记） | 写入方切片权限模型 |

---

## 10. 本轮未做 / 禁止事项（如实声明）

1. 未修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码、READY_QUEUE*/WORK_STATE/CCR-W2-API-001 主索引/WAVE_2_TEST_ACCEPTANCE——仅新增本目录与 CCR 草案目录内文件。
2. 未实现任何排期/订单/支付/hold/swap 代码（AGENTS 硬规则；SCH-001 实现待 CCR 批准后另派）。
3. 未提前实现 SCH-002 人员容量联动、SCH-003 生命周期、运营排期监管路由、写入方命令。
4. B5 售罄口径、T1 矛盾勘误等仅登记建议，不代产品裁决。
5. 测试本轮不跑（准备阶段）；测试计划见 TEST-PLAN.md，执行待实现切片。
