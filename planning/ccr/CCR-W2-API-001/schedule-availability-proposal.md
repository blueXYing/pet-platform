# CCR-W2-API-001 排期域可用性查询契约提案（v0.2）

状态：**PROPOSAL_ACCEPTED（2026-09-23 人工批准 SCH-D1～D11，见[决定回执](schedule-availability-decisions.md)；D6 按裁决修改为"真实路由失败关闭"口径）**。规范版本：0.2，日期：2026-09-23。
提出方/唯一编辑者：Backend Core（SCH-001 规范阶段，角色：排期域后端）。关联 Issue：SCH-001 / EPIC-05 / ST-SCH-01；下游：SCH-002/SCH-003/TX-001、C-003、M-002。批准人：人工 Contract Owner **blueXYing**。
基线：develop `639b61a`；分支 `codex/sch001-prep-20260923`。v0.2 起按批准同步权威文档（07/10/11；12 号无变化）并派发实现。
配套核验：[SCOPE-VERIFY.md](../../issues/wave-2/SCH-001-prep/SCOPE-VERIFY.md)（S1～S4 事实盘点）、[TEST-PLAN.md](../../issues/wave-2/SCH-001-prep/TEST-PLAN.md)。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-23） | 初稿，SCH-D1～D11 |
| 0.2（2026-09-23，批准落档） | 人工批准 SCH-D1～D11；**D6 按裁决重写**：SQL 种子与容量占位值仅限模块测试；真实接口不得把 `effectiveCapacity=configuredCapacity`、人员数占位、`occupiedCount=0` 当作已核实可约事实返回 `available=true`（SSOT §12 容量=min(配置,可用人员)）；**缺少权威事实时失败关闭（503 COMMON_DEPENDENCY_UNAVAILABLE）**；装配开关默认关闭；交付口径=模块测试（种子事实）+契约。SCH-D11 增勘误登记（C 端 PRD"商家或运营维护"句，不改 docx 原文）；SCH-D7 明确 SCH-004 承接后端写入、M-002 承接商家页面 |

## 人工 CTO 一页阅读指南

**用通俗话说：小程序"选预约时间"页的查询接口，字段和规则已成书面约定并获批。关键点两条：一是没有商家排期管理页，窗口数据只能靠测试直接写库验证——这只能用于模块测试，不能对外宣称"可约事实已核实"；二是"实际可预约容量=min(商家配置,可用人员)"里的"可用人员"事实源要到 SCH-002 才有，在那之前真实接口一律按"依赖不可用"报 503 关闭，绝不拿配置容量冒充已核实容量。写入方（商家排期管理）另立 SCH-004 后端切片+M-002 页面切片。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐字段/校验/错误/会话映射、窗口聚合与失败关闭规则、内部 ScheduleQueryApi 形状、示例与反例、Schema/Event/OpenAPI 影响与依赖核验；按批准实现切片 |
| 你负责 | 已批准（2026-09-23）；SCH-002/003/004 契约与写入方派发另批 |
| 不变 | 产品规则（分钟排期不固定 60 分钟槽/容量 min 公式/120 分钟约束/必须预约/改期一次）原样执行，不提前实现 hold/订单/支付/人员容量事实源 |

## 已批准决定（SCH-D1～D11；D6 为裁决修改版）

1. **SCH-D1 会话语义＝登录态，不扩 STR-D8（按推荐批准）**：`GET /api/v1/c/services/{serviceId}/availability` 维持 C 端强制登录（无 token 401 `COMMON_UNAUTHORIZED`、携带 Bearer 校验、无效/过期仍 401）。STR-D8 匿名四路由不含本路由；PRD 依据=C 端 §5.1.13「所有用户浏览；已登录用户可进入预约」、§5.1.15 预约服务页角色=已登录宠物主。既有 `CBearerSessionFilter.protectedPath` 前缀 `/api/v1/c/services/` 已覆盖本路由，`optionalSessionPath` 不匹配两段路径——**过滤器零改动**；安全链新增 permitAll 登记（GET、`pet.schedule.query.enabled` 门控）。读操作无 requestId 幂等要求（23 号 §3）。
2. **SCH-D2 日期范围与窗口边界（按推荐批准）**：Query `startDate`/`endDate` **必填**，格式 `yyyy-MM-DD`（无偏移，按平台业务时区 Asia/Shanghai（23 号 §2）解释为 `[startDate 00:00, endDate+1 00:00)`）；`endDate>=startDate`；跨度 **≤31 天**（超限 400）。响应过滤：`end_at<=now`（已结束）窗口不返回；`start_at<=now<end_at`（进行中）窗口返回且 `available=false`；**CLOSED 窗口不返回**；**跨天窗口与查询区间部分相交时整体返回不切割**。无可约窗口=200 空 `items`（与 404/503 严格三区分）。items 按 `start` 升序；存储窗口状态非 OPEN（含未知值）一律不返回（向"不可约"方向失败关闭）。
3. **SCH-D3 无写入方如实披露（批准；结合 D6 强化）**：V1 尚无 `schedule_availability_window` 任何写路径（商家排期管理页未实现，HTTP10 §4.8 仅路由壳；运营端明令不代商家维护；入驻不建排期）。**SQL 种子与容量占位值只能用于 SCH-001 的模块测试**；不得据此声明"商家开窗→消费者查到→预约成功"完整流程验收；ORD-006/007 仅报模块证据。真实 E2E 在写入方（SCH-004+M-002）交付前保留未完成。
4. **SCH-D4 上门接送型双时段呈现=窗口不分 kind（批准）**：返回窗口集不区分"上门候选/送回候选"——同一服务窗口同时作为两类候选，120 分钟间隔与跨天约束不在查询侧（C 端置灰联动；服务端最终校验归 SCH-003 hold/订单侧）。**上门/送回分别开窗须在写入方交付时解决 `window_kind`（06 号增补届时走 CCR），不得把同一组窗口宣称为已完成双时段排期**（裁决原文）。Schema 缺口（表无窗口类型列，PRD §5.4 字段表有上门/送回两字段）如实登记。
5. **SCH-D5 提前预约窗口=V1 不引入（批准）**：C 端/商家端/运营端 PRD 与 SSOT 均无"最短提前预约时长/最远可约天数"条款；不自行造规则；唯一时间门禁=D2 过去窗口过滤；最远可约由 31 天跨度上限间接约束。
6. **SCH-D6 容量语义与失败关闭（裁决修改版，本提案核心）**：
   - **公式不变**：`effectiveCapacity = min(configuredCapacity, qualifiedAvailableStaffCount)`（SSOT §12.2）。`occupiedCount` 读取 `schedule_reservation` 权威表（TEMP_LOCKED/CONFIRMED 与窗口重叠计数；当前无生产者，读出即 0——这是读权威表的事实，不是占位假设）。
   - **SQL 种子与容量占位仅限模块测试**：模块测试以种子事实（窗口行+人员计数测试替身+可选预约行）验证聚合逻辑正确性。
   - **真实接口失败关闭**：人员可用性事实源（SCH-002 商家域第六查询+SCH 域能力/时间窗）交付前，真实装配**不提供人员事实提供器**；可见服务的 availability 查询在进入容量计算时**失败关闭 503 `COMMON_DEPENDENCY_UNAVAILABLE`**（对齐既有先例），**不得降级为 `available=true`、不得用 configuredCapacity 冒充已核实容量、不得输出伪人员数**。
   - **错误两分次序**：服务不可见（四条件不合取/storeId 不匹配/不存在）→ 404 `SERVICE_NOT_FOUND`（可见性事实可核实，先判）；可见 → 人员事实缺失 → 503。两者不混同。
   - **交付口径**：本切片=模块测试（种子事实）+契约+失败关闭装配；**不声明可约事实已核实、不声明完整可约判断启用**；面向用户启用完整可约判断须等 SCH-002 人员容量及后续占用事实接通。装配开关 `pet.schedule.query.enabled` 默认关闭。
7. **SCH-D7 排期写入方拆分（批准，归属定稿）**：**后端写入方=SCH-004 承接**（§4.8 四路由命令+§4.9 人员时间+`window_kind` 增补+临时停业拦截+排期负责人 RBAC+运营只读监管路由）；**商家排期管理页=M-002 承接**。真实"开窗→查询→预约"验收前必须交付写入方，但不阻塞 SCH-001 独立读测试。
8. **SCH-D8 错误码与 OpenAPI（按推荐批准）**：404 `SERVICE_NOT_FOUND`（不可见/storeId 不匹配，SVC-D1b 同响应防探测）；503 `COMMON_DEPENDENCY_UNAVAILABLE`（事实源故障**及人员事实缺失失败关闭**）；400 `COMMON_INVALID_ARGUMENT`；401 `COMMON_UNAUTHORIZED`。12 号无新增（SCHEDULE_* 8 码留 SCH-003 命令侧）；11 号增补 `cGetServiceAvailability`（状态 `IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS`——已实现、默认关闭、真实容量需提供器，对齐服务写入方先例）。
9. **SCH-D9 storeId 一致性（按推荐批准）**：必填；与服务归属不一致 → 404 `SERVICE_NOT_FOUND`（防探测，先例 checkBookable 内建同语义）。
10. **SCH-D10 items 状态呈现（按推荐批准）**：布尔 `available`，不新增 status 枚举；不向 C 端暴露"已锁定"内部细节；商家端日历细分状态归 SCH-004。
11. **SCH-D11 T1 矛盾勘误登记（批准）**：C 端 PRD §5.1.15「商家排期可由商家或运营维护」与运营端 PRD §3.3/§6.2.5 矛盾——按运营端口径执行：**排期写入仅商家（运营只读监管）**；C 端该句**登记勘误**（随决定回执与 issue 文档落盘，**不改 PRD docx 原文**）。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| Schema 06 §2 | `schedule_availability_window` 已定（merchant/store/service/start_at/end_at/configured_capacity/status OPEN-CLOSED/version；idx_schedule_service_time）；`schedule_reservation` 已定（占用读取用，无生产者）；`staff_availability_window`/`staff_service_capability` 已定无契约（SCH-002/SCH-004） |
| 内部 API07 §6.1/§6.2/§6.3 | `queryAvailability` 接口已列、`AvailabilityQuery` 形状未定义（v0.2 补：§3）；`AvailabilityResult` 单区间已定（list 化为窗口 DTO，字段不变）；§6.2 命令规则全留 SCH-003 |
| HTTP10 §3.4 | 路由+Query+items 示例+四规则已列；v0.2 补会话/校验/错误/边界/失败关闭语义 |
| 错误码 12 号 | 全沿用，无新增（SCH-D8） |
| OpenAPI 11 号 | 无 availability 操作；v0.2 增补（SCH-D8） |
| SVC-001 事实源 | `ServiceQueryApi.checkBookable`（四条件+reasonCodes+storeId 不匹配 404 内建）已交付，SCH 入口直接消费 |
| pet-merchant-api | 五查询已批；**无门店在职可服务员工查询**（SCH-002 前置缺口登记） |
| 模块现状 | pet-schedule-api/biz 空壳，本切片首次填充；biz pom 依赖已备（merchant-api/service-api/task-core/redis/mybatis） |

## 2. HTTP 操作契约（随本切片同步 10 号 §3.4）

会话（SCH-D1）：C 端登录态（既有 `/api/v1/c/services/` 前缀强制会话；匿名 401 `COMMON_UNAUTHORIZED`；无效/过期 Bearer 401）。读操作无 requestId 要求。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/services/{serviceId}/availability?storeId&startDate&endDate` | 200 `ApiResponse<{items:[...]}>`（仅当人员事实提供器在场——当前仅模块测试装配；见 D6） | 404 `SERVICE_NOT_FOUND`（不存在/不可见/storeId 不匹配）；**503 `COMMON_DEPENDENCY_UNAVAILABLE`（事实源故障或人员可用性事实缺失失败关闭）**；400 `COMMON_INVALID_ARGUMENT`；401 `COMMON_UNAUTHORIZED` |

Query 校验（违规 400，details 指明字段）：`serviceId`/`storeId` 雪花 ID 十进制 String（10 号 §2.5）；`startDate`/`endDate` `yyyy-MM-DD` 必填、`endDate>=startDate`、跨度 ≤31 天（SCH-D2）。

成功响应（种子事实下的模块测试示例）：

```json
{
  "items": [
    {
      "start": "2026-10-12T09:15:00+08:00",
      "end": "2026-10-12T18:10:00+08:00",
      "effectiveCapacity": 3,
      "occupiedCount": 0,
      "remainingCapacity": 3,
      "available": true
    }
  ]
}
```

- 时间带偏移 ISO-8601、分钟精度（存储 DATETIME(3) UTC 解释，23 号 §2；HTTP 投影业务时区 +08:00）；不暴露 windowId/version/status/configuredCapacity 细分（SCH-D10）。
- 上门接送型：窗口集不分 kind（SCH-D4）；响应不携带 fulfillmentType。
- **真实装配（无人员事实提供器）**：可见服务的查询一律 503 失败关闭（D6）——即生产开启开关后本路由当前**不产出任何 available=true 事实**；这是有意的安全交付状态，不是缺陷。
- **边界明示**：本查询仅为展示，不构成预约授权租约（27 号 §4 同语义）；所选时段下单瞬间可能失效，由 SCH-003/TX-001 承接。

反例（模块测试逐项断言状态码+body code）：

| 场景 | 预期 |
|---|---|
| 服务不可见（不存在/OFFLINE/DRAFT/REVIEWING/REJECTED/商家停用/门店停用/不接新单）——无人员提供器时同 | 404 `SERVICE_NOT_FOUND`（可见性先判，人员缺失不掩盖不可见） |
| storeId 与服务归属不一致 | 404 `SERVICE_NOT_FOUND` |
| 服务可见+人员事实提供器缺席（真实装配） | 503 `COMMON_DEPENDENCY_UNAVAILABLE`（D6 失败关闭） |
| 服务/商家事实读取失败或状态未知 | 503 `COMMON_DEPENDENCY_UNAVAILABLE` |
| 服务可见+提供器在场+范围内无 OPEN 窗口（全 CLOSED/未开窗） | 200 空 items（种子模式断言） |
| 已结束窗口 / 进行中窗口 / CLOSED 窗口 | 前者不返回；中者返回 available=false；后者不返回 |
| 跨度 32 天 / endDate<startDate / 日期非法 / ID 非法 / 缺参 | 400 `COMMON_INVALID_ARGUMENT` |
| 无 Authorization / 无效 Bearer | 401 `COMMON_UNAUTHORIZED` |

## 3. 内部 API 契约（随本切片同步 07 号 §6.1）

pet-schedule-api 落地（`QueryContext` 三字段不扩展）：

```java
public record AvailabilityQuery(
    String serviceId, String storeId,
    java.time.LocalDate startDate, java.time.LocalDate endDate,
    QueryContext context) {}

/** 逐窗口聚合；字段沿用 07 号 §6.3 AvailabilityResult（list 化，语义不变）。 */
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

- `ScheduleQueryApi.queryAvailability(AvailabilityQuery)` 返回 `AvailabilityPageDTO`；**`getReservation`/`ScheduleCommandApi` 一字不动（SCH-003 范围，实现随其切片落地）**。
- 聚合规则（pet-schedule-biz）：①入口经 `ServiceQueryApi.checkBookable`（service 域自持 repeatable-read 快照）——NOT_FOUND/不可见即 404 投影，事实源故障 503 透传；②窗口读 `schedule_availability_window`（SCH 域自有 Mapper，独立快照；与①为两个连续快照，跨快照漂移由 SCH-003 hold 权威复核兜底，文档如实登记）；③**人员事实经 `QualifiedStaffFactsPort`（pet-schedule-biz 端口）：装配无提供器 → 503 失败关闭（D6）；提供器在 → effectiveCapacity=min(configured, count)**，提供器异常同样 503；④`occupiedCount` 读 `schedule_reservation`（TEMP_LOCKED/CONFIRMED 重叠计数）；⑤过滤/排序按 SCH-D2；⑥任何事实源异常/未知 → 503，绝不降级为可约或空页冒充 503。
- `QualifiedStaffFactsPort` 是 SCH-002 契约的占位端口形状（`int countQualifiedAvailableStaff(String storeId, String serviceId, OffsetDateTime from, OffsetDateTime to)`）；真实实现=商家域第六查询∩SCH 域能力/时间窗，随 SCH-002 另行走 CCR——本切片不实现、不定义其内部语义。
- biz 不依赖 biz、不跨模块 Repository（service 事实经 pet-service-api）；ARCH001~005 随 CI。

## 4. Schema / Event / OpenAPI 影响

- **Schema：无变化**（06 号既有表满足；`window_kind` 缺口登记 SCH-D4，SCH-004 时裁决）。
- **Event：无**（纯读切片）。
- **内部契约：pet-schedule-api 落地 §3 形状**；pet-merchant-api 无变化。
- **权威同步（随本切片 PR）**：07 号 §6.1 形状+D6 失败关闭规则+两快照注记、§6.3 list 化注记；10 号 §3.4 全语义细化（含失败关闭）；11 号增补 `cGetServiceAvailability`（`IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS`）；12 号无变化。
- **安全装配**：`CSessionSecurityConfiguration` 增 GET `/api/v1/c/services/*/availability` permitAll（`pet.schedule.query.enabled` 门控，行级追加）；`CServiceExceptionHandler` assignableTypes 增 CScheduleController（STATUS 无新增，全部既有映射覆盖）；`CBearerSessionFilter` 零改动。

## 5. 测试映射（TEST-PLAN v0.2；编号 W2-SCH-001～007）

| 验收 | 本提案对应 |
|---|---|
| W2-SCH-001 窗口查询聚合 | §2/§3：种子窗口→items 字段/分钟区间/升序/跨天整体返回 |
| W2-SCH-002 可见性与错误两分 | 四条件反例 404、事实源故障 503、空窗口 200（三区分） |
| W2-SCH-003 字段/校验/会话 | ID String、日期/跨度 400、401 匿名、storeId 不匹配 404 |
| W2-SCH-004 履约方式投影 | 双履约型同呈现不分 kind；120min 不在查询侧 |
| W2-SCH-005 时间边界 | 已结束过滤/进行中 false/时区解释/CLOSED 不进 items/31 天边界 |
| W2-SCH-006 容量聚合（种子模式） | min(配置,人员替身) 生效、occupied 读权威表（种子预约行）、remaining/available 派生 |
| W2-SCH-007 真实装配失败关闭（D6 裁决核心） | 无人员事实提供器时：不可见→404 先判；可见→503 COMMON_DEPENDENCY_UNAVAILABLE；绝不 available=true/伪容量；与种子模式（提供器在场 200）区分断言 |
| ARCH001~005 | 不跨模块 Repository；biz 不依赖 biz |
| ORD-006/007 | 仅模块证据（D3/D6 披露） |

## 6. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 排期后端写入方（§4.8/§4.9 命令+window_kind+临时停业拦截+排期负责人 RBAC+运营只读监管） | **SCH-004**（裁决定稿） | 本回执+依赖图既有（批准时 Owner 登记 READY_QUEUE） |
| 商家排期管理页 | **M-002**（裁决定稿） | 同上 |
| effectiveCapacity 人员事实源（商家域第六查询+SCH 域能力/时间窗组合） | SCH-002 | 依赖图既有；SCOPE-VERIFY S4.1 |
| hold/swap/TEMP_LOCKED/幂等 | SCH-003（27 号 §7 先例） | 既有归属 |
| C 端 PRD §5.1.15"商家或运营维护"句勘误 | 已登记（回执+issue 文档），不改 docx | SCH-D11 |

风险与如实披露：

- 真实装配下可见服务查询恒 503（人员事实缺席）——**有意交付状态**（D6）；上线排期需与 SCH-002 交付联动，PR 描述与开关文档明示。
- 两连续快照（service 事实/窗口）间存在理论漂移窗口，展示查询可接受，权威判定在 SCH-003 hold。
- 写入方未交付前"商家开窗→消费者查到"链路未验收（D3）；夹具播种在测试文档显式标注。
- SCH-D4 若 SCH-004 裁决分 kind，本查询响应可能增列（向后兼容，届时 CCR）。
