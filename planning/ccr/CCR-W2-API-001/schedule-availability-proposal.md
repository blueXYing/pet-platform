# CCR-W2-API-001 排期域可用性查询契约提案（草案 v0.1）

状态：**DRAFT — 未获批不得实现**（等待人工 Contract Owner 批准 SCH-D1～D11）。规范版本：0.1，日期：2026-09-23。
提出方/唯一编辑者：Backend Core（SCH-001 规范阶段，角色：排期域后端）。关联 Issue：SCH-001 / EPIC-05 / ST-SCH-01；下游：SCH-002/SCH-003/TX-001、C-003、M-002。批准人：人工 Contract Owner **blueXYing**。
基线：develop `639b61a`；分支 `codex/sch001-prep-20260923`。本草案只做规范与依赖核验，不写实现代码、不改权威 06/07/10/11/12；获批后由 Owner 同步权威文档再派发实现。
配套核验：[SCOPE-VERIFY.md](../../issues/wave-2/SCH-001-prep/SCOPE-VERIFY.md)（S1～S4 事实盘点）、[TEST-PLAN.md](../../issues/wave-2/SCH-001-prep/TEST-PLAN.md)。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-23） | 初稿，SCH-D1～D11。基于 SVC-001 读切片先例（SVC-D1～D5 已批）与 SVC-D4 无写入方披露模式起草 |

## 人工 CTO 一页阅读指南

**用通俗话说：小程序里"选预约时间"页要能跑，得先把"查某服务在某日期范围内有哪些可约时段"的接口字段和规则写成书面约定。数据库表 `schedule_availability_window` 和 HTTP 路由名早就定了，但查询参数怎么校验、要不要登录、查不到算谁的错、上门接送的双时段怎么展示、"有效容量"这个阶段算不算服务人员，全都没写。另外发现这张表目前没有任何写入方（商家排期管理页还没做），和服务域当时"读先行、写入方后补"的情况一模一样。本提案补齐读契约，并把十一个需要你拍板的问题列清楚；写入方另立切片，不混在本切片里。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐字段/校验/错误/会话映射、窗口聚合与失败关闭规则、内部 ScheduleQueryApi 形状、示例与反例、Schema/Event/OpenAPI 影响与依赖核验 |
| 你负责 | 审核"建议决定"十一项（SCH-D1～D11）；权威契约同步与实现派发按流程另批 |
| 必读 | 本页"建议决定"；细节见下文逐操作定义 |
| 不变 | 产品规则（分钟排期不固定 60 分钟槽/容量 min 公式归 SCH-002/120 分钟约束/必须预约/改期一次）原样执行，不提前实现 hold/订单/支付/人员容量联动 |

## 建议决定（需人工批准的十一项，DRAFT）

1. **SCH-D1 会话语义＝登录态，不扩 STR-D8**：`GET /api/v1/c/services/{serviceId}/availability` 维持 C 端强制登录（无 token 401 `COMMON_UNAUTHORIZED`、携带 Bearer 校验、无效/过期仍 401）。STR-D8 匿名四路由（`/c/stores`、`/c/stores/{storeId}`、`/c/stores/{storeId}/services`、`/c/services/{serviceId}`）**不含本路由**；PRD 依据=C 端 §5.1.13「所有用户浏览；已登录用户可进入预约」、§5.1.15 预约服务页角色=已登录宠物主。现状代码即登录态（`CBearerSessionFilter.protectedPath` 前缀 `/api/v1/c/services/`），**无安全装配改动**。备选=随浏览族匿名（第五路由），需你显式批准扩大匿名面并同步四文件安全登记。读操作无 requestId 幂等要求（23 号 §3）。
2. **SCH-D2 日期范围与窗口边界**：Query `startDate`/`endDate` **必填**，格式 `yyyy-MM-DD`（无偏移，按平台业务时区 Asia/Shanghai（23 号 §2）解释为 `[startDate 00:00, endDate+1 00:00)`）；`endDate>=startDate`；跨度 **≤31 天**（防全表拉取；`>31` → 400）。响应过滤：`end_at<=now`（已结束）窗口不返回；`start_at<=now<end_at`（进行中）窗口返回且 `available=false`；**CLOSED 窗口不返回**（items=OPEN 可约窗口集合）；**跨天窗口（寄养/过夜）与查询区间部分相交时整体返回不切割**。无可约窗口=200 空 `items`（与 404 严格区分）。items 按 `start` 升序。
3. **SCH-D3 无写入方如实披露（复刻 SVC-D4）**：V1 尚无 `schedule_availability_window` 任何写路径（商家排期管理页未实现，HTTP10 §4.8 仅路由壳；运营端明令不代商家维护；入驻不建排期）。本切片测试以 SQL 夹具直接播种，可证明"给定窗口数据，查询与可见性判断正确"，**不能证明"商家开窗→消费者查到→预约成功"完整流程**；真实 E2E 在写入方交付前保留未完成，ORD-006/007 仅报模块证据，不声明完整业务验收。`occupiedCount` 因 `schedule_reservation` 无生产者恒为 0，同条披露。
4. **SCH-D4 上门接送型双时段呈现=窗口不分 kind**：SCH-001 返回的窗口集不区分"上门候选/送回候选"——同一服务的窗口同时作为两类候选供 C 端分别选择，**120 分钟间隔与跨天送回约束不在查询侧**（C 端已做置灰联动，服务端最终校验归 SCH-003 hold/订单侧，07 号 §6.2 已有 `returnStart>=pickupStart+120min` 规则）。**如实登记 Schema 缺口**：`schedule_availability_window` 无窗口类型列，而商家端 PRD §5.4 字段表明确列出「上门时段/送回时段」两个条件必填字段——若写入方需要分别维护两类窗口，须届时增补 `window_kind` 列（06 号变更走 CCR）；备选=本切片一并提 06 号增补（不推荐：读侧用不到，先改 Schema 属扩大范围）。
5. **SCH-D5 提前预约窗口=V1 不引入**：C 端/商家端/运营端 PRD 与 SSOT 均**无**"最短提前预约时长/最远可约天数"条款（C 端仅"默认当天，支持切换"）。不自行造规则；唯一时间门禁=SCH-D2 的过去窗口过滤；最远可约由 31 天跨度上限间接约束。若产品实际需要（如"至少提前 30 分钟"），请在裁决时给出规则值，SCH-003 hold 校验同步引入。
6. **SCH-D6 内部 ScheduleQueryApi 形状与阶段容量语义（07 号 §6.1 细化）**：`AvailabilityQuery`（现仅名称引用）定为 `record AvailabilityQuery(String serviceId, String storeId, LocalDate startDate, LocalDate endDate, QueryContext context)`；返回**窗口列表** `AvailabilityWindowDTO[]`（逐窗口含 07 号 §6.3 已批字段：storeId/serviceId/start/end/configuredCapacity/**qualifiedAvailableStaffCount**/effectiveCapacity/occupiedCount/remainingCapacity/available——list 化后单窗口字段保留，`getReservation` 留 SCH-003 不动）。**阶段语义显式锁定：SCH-001 时 `effectiveCapacity=configuredCapacity`、`qualifiedAvailableStaffCount` 不参与（返回 configuredCapacity 同值并在实现注释标注占位）、`occupiedCount=0`**——Issue Catalog 已把 `min(configured,availableStaff)` 划给 SCH-002（ISSUE_CATALOG.csv:13），本切片不假装已算人员容量；SCH-002 获批后本字段语义升级为 min 公式（届时有独立 CCR，回归测试同步）。失败关闭：窗口表读取失败/状态值未知 → 503，不降级空页。
7. **SCH-D7 排期写入方另立切片承接**：HTTP10 §4.8 四路由（availability-windows CRUD）+ §4.9（人员可用时间）+ 商家排期管理页 + 临时停业拦截（「已有订单占用的时段不得关闭或降容量」）+ `window_kind` Schema 增补裁决 + 排期负责人 RBAC 映射（10 号:1935）+ 运营只读监管路由（运营端 §6.2.5），**全部不在 SCH-001**；建议编号 **SCH-004（候选）** 或归 M-002 排期页切片，归属与 Owner 由你派发（本提案仅登记，不改依赖图/READY_QUEUE）。运营端写入明令禁止（§3.3「不代商家维护排期」，矛盾 T1 见 SCOPE-VERIFY S1.3，推荐按运营端 PRD 仅商家）。
8. **SCH-D8 错误码与 OpenAPI 全沿用，不新增**：不可见（不存在/OFFLINE/DRAFT/REVIEWING/REJECTED/商家停用/门店停用/不接新单）→ 404 `SERVICE_NOT_FOUND`（SVC-D1b 同响应不区分原因，防探测）；事实源故障 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`；参数 → 400 `COMMON_INVALID_ARGUMENT`（details 指明字段）；未登录 → 401 `COMMON_UNAUTHORIZED`。12 号 **无新增**（SCHEDULE_* 8 码留给 SCH-003 命令侧）；11 号获批后增补操作 `cGetServiceAvailability`（`ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步，MER-001/SVC-001 同款披露）。
9. **SCH-D9 storeId 一致性=必填+不匹配 404**：Query `storeId` 必填；与服务归属门店不一致 → 404 `SERVICE_NOT_FOUND`（防探测，先例 `ServiceQueryService.bookability` 对 storeId 不匹配同走 notFound，backend/pet-service-biz:70-72）。
10. **SCH-D10 items 状态呈现=布尔 available，不新增 status 枚举**：商家端 PRD §5.4「可用状态=可约/已锁定/已占满/已关闭（前后台共用）」在 C 端投影为 `available=false` 统一表达不可约/已占满（本阶段 remainingCapacity=effectiveCapacity 时恒 true 或进行中 false）；不向 C 端暴露"已锁定"内部细节（防探测/防把锁定当剩余）。商家端日历的细分状态由写入方切片（SCH-D7）另行定义。
11. **SCH-D11 T1 矛盾登记（C 端 PRD vs 运营端 PRD）**：C 端 PRD §5.1.15「商家排期可由商家或运营维护」与运营端 PRD §3.3/§6.2.5「不代商家维护排期、只读监管」矛盾。按资料优先级推荐**运营端口径：写入方仅商家**；C 端该句建议后续勘误（本草案不改 PRD，仅登记）。不阻塞 SCH-001。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| Schema 06 §2 | `schedule_availability_window` 已定（merchant/store/service/start_at/end_at/configured_capacity/status OPEN-CLOSED/version；idx_schedule_service_time）；`schedule_reservation`/`staff_availability_window` 已定但生产者未交付；`staff_service_capability` 已定无契约 |
| 内部 API07 §6.1/§6.2/§6.3 | `ScheduleQueryApi.queryAvailability/getReservation` 与 `ScheduleCommandApi` 五命令接口已列；**`AvailabilityQuery`/`ReservationQuery` 仅名称引用形状未定义（本提案 §3 补 AvailabilityQuery）**；`AvailabilityResult` 单区间已定；§6.2 规则（分钟级/无 60 分钟槽/120min/min 容量/hold 过期/swap 原子/退款不释放）全部留给 SCH-003，不在本切片 |
| HTTP10 §3.4 | 路由+Query（storeId/startDate/endDate）+items 示例+四规则已列；**会话/错误/边界/时区/排序/空结果未定义（本提案 §2 补）** |
| HTTP10 §4.8/§4.9 | 商家排期/人员时间路由壳，写入方缺口（SCH-D7 承接登记） |
| 错误码 12 号 | SERVICE_NOT_FOUND/COMMON 族既有；SCHEDULE_* 留命令侧；无新增（SCH-D8） |
| OpenAPI 11 号 | 无 availability 操作；获批后增补 cGetServiceAvailability（SCH-D8） |
| SVC-001 事实源 | `ServiceQueryApi.checkBookable`（四条件+reasonCodes）已交付，SCH 入口可见性直接消费；`getServiceSnapshot`（含 durationMinutes/fulfillmentType）直接消费 |
| pet-merchant-api | 五查询已批（三查询+成员 getFacts+展示资格第四/第五）；**无门店在职可服务员工查询**（SCH-002 前置缺口，S4.1 登记，本切片不定义） |
| 模块现状 | pet-schedule-api/biz 纯 package-info 空壳；biz pom 已依赖 pet-merchant-api/pet-service-api/pet-task-core/redis/mybatis（符合架构，无需改） |
| 写入方 | 无（SCH-D3）；pet-schedule-biz 首次填充读实现 |

## 2. HTTP 操作契约（获批后同步 10 号 §3.4 细化）

会话（SCH-D1）：C 端登录态（既有 `/api/v1/c/services/` 前缀强制会话，无装配改动）；读操作无 requestId 要求（23 号 §3）。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/services/{serviceId}/availability?storeId&startDate&endDate` | 200 `ApiResponse<{items:[...]}>`，items=OPEN 窗口按 start 升序 | 404 `SERVICE_NOT_FOUND`（不存在/不可见/storeId 不匹配，SCH-D9）；503 `COMMON_DEPENDENCY_UNAVAILABLE`（事实源故障）；400 `COMMON_INVALID_ARGUMENT`（ID/日期格式/endDate<startDate/跨度>31 天）；401 `COMMON_UNAUTHORIZED` |

Query 校验（违规 400，details 指明字段）：

- `serviceId`（路径）：雪花 ID 十进制 String（公共 ID Codec，10 号 §2.5）；
- `storeId`（query）：同上，必填（SCH-D9）；
- `startDate`/`endDate`：`yyyy-MM-DD`，必填，`endDate>=startDate`，跨度 ≤31 天（SCH-D2）。

响应投影（成功示例）：

```json
{
  "items": [
    {
      "start": "2026-10-12T09:15:00+08:00",
      "end": "2026-10-12T18:10:00+08:00",
      "effectiveCapacity": 5,
      "occupiedCount": 0,
      "remainingCapacity": 5,
      "available": true
    }
  ]
}
```

- 时间带偏移 ISO-8601、分钟精度（非零亚毫秒拒绝，23 号 §2）；`windowId`/`version`/`status`/`configuredCapacity` 细分不暴露（available 布尔统一，SCH-D10）。
- 上门接送型：窗口集不分 kind，两类候选共用（SCH-D4）；响应不携带 fulfillmentType（详情已有，不重复）。
- 阶段容量语义（SCH-D6）：`effectiveCapacity=configuredCapacity`、`occupiedCount=0`、`remainingCapacity=effectiveCapacity`、`available=OPEN∧未过滤`（进行中窗口 available=false，SCH-D2）。
- **边界明示**：本查询仅为展示，"不构成预约授权租约"（27 号 §4 同语义）；selected 时段在下单瞬间可能失效（C 端 PRD §5.1.15「排期瞬间变化…请重新选择」由 SCH-003/TX-001 承接）。

反例（正反 Mock 随权威同步）：

| 场景 | 预期 |
|---|---|
| 服务 ACTIVE+四条件通过+窗口 OPEN | 200 items 含该窗口 |
| 服务 OFFLINE/DRAFT/REVIEWING/REJECTED、商家 OFFLINE、门店 FROZEN、不接新单 | 404 `SERVICE_NOT_FOUND`（不区分原因） |
| storeId 与服务归属不一致 | 404 `SERVICE_NOT_FOUND`（SCH-D9） |
| 窗口表读取失败/status 未知值 | 503 `COMMON_DEPENDENCY_UNAVAILABLE`（不降级空页） |
| 服务可见但该范围内无 OPEN 窗口（含全 CLOSED/临时停业未开窗） | 200 空 items（非 404、非 503） |
| 跨度 32 天 / endDate<startDate / 日期非法 / ID 非法 | 400 `COMMON_INVALID_ARGUMENT` |
| 无 Authorization | 401 `COMMON_UNAUTHORIZED` |
| 已结束窗口 / 进行中窗口 | 前者不返回；后者返回且 available=false |

## 3. 内部 API 契约（获批后同步 07 号 §6.1）

pet-schedule-api 落地（`QueryContext` 三字段不扩展，07 号 §2.2/§21）：

```java
public record AvailabilityQuery(
    String serviceId, String storeId,
    java.time.LocalDate startDate, java.time.LocalDate endDate,
    QueryContext context) {}

/** 逐窗口聚合；单窗口字段沿用 07 号 §6.3 已批 AvailabilityResult 语义（list 化）。 */
public record AvailabilityWindowDTO(
    String storeId, String serviceId,
    java.time.OffsetDateTime start, java.time.OffsetDateTime end,
    int configuredCapacity, int qualifiedAvailableStaffCount,
    int effectiveCapacity, int occupiedCount, int remainingCapacity,
    boolean available) {}

public record AvailabilityPageDTO(
    String storeId, String serviceId,
    java.time.LocalDate startDate, java.time.LocalDate endDate,
    java.util.List<AvailabilityWindowDTO> items) {}
```

- `ScheduleQueryApi.queryAvailability(AvailabilityQuery)` 返回 `AvailabilityPageDTO`（接口签名不变，返回类型在 07 号 §6.1 细化为本形状）；`getReservation`/`ScheduleCommandApi` 五命令**一字不动**（SCH-003 范围）。
- 聚合规则（pet-schedule-biz，实现阶段）：入口经 `ServiceQueryApi.checkBookable`（同事务 repeatable-read 快照，SVC-D5 joining 先例）取四条件——不可见即 NOT_FOUND 投影 404；窗口读 `schedule_availability_window`（本模块自有 Mapper，`store_id+service_id+start_at` 索引）；窗口过滤/排序按 SCH-D2；容量按 SCH-D6 阶段语义。任何事实源异常/未知 → 503 失败关闭，绝不降级为可约或空页冒充 503。
- biz 不依赖 biz、不跨模块 Repository（service/merchant 事实均经 API）；架构守卫 ARCH001~005 随 CI。

## 4. Schema / Event / OpenAPI 影响

- **Schema：无变化**（06 号既有表满足读侧；`window_kind` 缺口只登记 SCH-D4，由写入方切片裁决增补；无迁移）。
- **Event：无**（纯读切片，无 outbox 事件；hold/confirm 事件归 SCH-003 另行 CCR）。
- **内部契约：pet-schedule-api 落地 §3 形状**；pet-merchant-api 无变化（SCH-002 增补另走 CCR）。
- **07 号权威同步**：§6.1 AvailabilityQuery/AvailabilityPageDTO 形状+阶段容量语义注记（SCH-D6）；**10 号权威同步**：§3.4 会话/校验/错误/边界/排序/空结果细化；**11 号**：增补 cGetServiceAvailability（x-contract-status=ACCEPTED_CONTRACT_NOT_IMPLEMENTED、x-audience=MINIAPP、security=登录态 bearerAuth（若 SCH-D1 备选被选则匿名可选）、404/503 描述对齐）；**12 号：无新增**。
- **安全装配：无改动**（`/api/v1/c/services/` 前缀既有强制会话已覆盖本路由；`CServiceExceptionHandler` 需登记 availability 控制器（若单独控制器则 assignableTypes 增补，实现阶段行级追加登记））。

## 5. 测试映射（WAVE_2_TEST_ACCEPTANCE 编号 W2-SCH-001～006，登记由该文件唯一 Writer 执行）

| 验收 | 本提案对应 |
|---|---|
| W2-SCH-001 窗口查询聚合 | §2/§3：SQL 播种窗口（SCH-D3）→items 字段/分钟区间/升序/跨天窗口整体返回 |
| W2-SCH-002 可见性与错误两分 | §2 反例表：四条件反例 404、事实源故障 503、空窗口 200 空 items，三者不混同 |
| W2-SCH-003 字段/校验/会话 | ID String（>2^53 往返）、日期格式/跨度/顺序 400、401 匿名、storeId 不匹配 404 |
| W2-SCH-004 履约方式投影 | IN_STORE/PICKUP_DELIVERY 窗口同呈现不分 kind；120min 不在查询侧（不实现不测试该约束） |
| W2-SCH-005 时间边界 | 已结束窗口过滤、进行中 available=false、时区 Asia/Shanghai 解释、CLOSED 不进 items |
| W2-SCH-006 阶段容量语义 | effectiveCapacity=configuredCapacity、occupiedCount=0 快照断言（SCH-002 升级时回归基线） |
| ARCH001~005 | 不跨模块 Repository；biz 不依赖 biz（schedule-biz 仅经 service/merchant API） |
| ORD-006/007 | 仅模块证据（SCH-D3 披露），不声明完整预约 E2E |

## 6. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 排期写入方（§4.8 四路由命令+商家排期页+§4.9 人员时间+window_kind 增补+临时停业拦截+排期负责人 RBAC） | **SCH-004（候选）或 M-002 排期页切片**（SCH-D7，归属待派） | CCR-W2-API-001 索引"可用性/排期管理"行（批准时由 Owner 登记） |
| 运营排期只读监管路由（运营端 §6.2.5） | 运营域后续切片 | 同上 |
| effectiveCapacity 升级 min(configured,availableStaff)+商家域第六查询（门店在职可服务员工） | SCH-002 | 依赖图既有；缺口在 SCOPE-VERIFY S4.1 |
| TEMP_LOCKED 生命周期/hold/swap/幂等 | SCH-003（27 号 §7 执行序先例） | 既有归属 |
| B5 售罄口径 | 建议 SCH-002 启动前裁决（推荐不实现，无位可约由 available=false 表达） | ROUND-CLOSEOUT 遗留表 |

风险与未决：

- **无写入方（SCH-D3）**→"商家开窗→消费者查到"真实链路未验收；夹具播种路径在测试文档显式标注。
- SCH-D6 阶段容量语义若被误读为"最终容量语义"，会在 SCH-002 交接时产生字段语义漂移——已在提案/测试双处锁定基线（W2-SCH-006）。
- SCH-D4 若最终裁决要分 kind，06 号 Schema 变更+本查询响应可能需要携带 kind 字段（向后兼容增列，届时 CCR）。
- SCH-D1 备选（匿名）若被选中，安全装配四文件需行级追加（CBearerSessionFilter optionalSessionPath/CSessionSecurityConfiguration/OpenAPI security/异常处理器），涉与既有匿名四路由同一登记面。
