# SCH-004 排期写入方切片 — 测试计划（v0.1 DRAFT，随裁决修订）

日期：2026-09-24。分支 `codex/sch002-prep-20260924`。
前置：CCR 草案 [schedule-write-proposal.md v0.1（SCHW-D1～D10，**DRAFT 未获批**）](../../../ccr/CCR-W2-API-001/schedule-write-proposal.md)；实现切片交付时逐项报告 PASS/FAIL/NOT_EXECUTED 及所用规范、数据库、应用提交。
编号风格对齐 [WAVE_2_TEST_ACCEPTANCE.md](../../WAVE_2_TEST_ACCEPTANCE.md)。**新编号 W2-SCHW-001～013 为本切片验收条目，登记进 WAVE_2_TEST_ACCEPTANCE.md 由该文件唯一 Writer 执行，本切片不改该共享文件。** SCHW-D 裁决后本计划对应条目定稿——重叠策略（D3 备选）、占用守卫范围（D4 备选）、临时停业（D5）、监管延后（D8）等条目按裁决取一。

## 0. 数据事实双轨说明（延续 SCH-D3 披露框架）

- **真实写入链路**（本切片新增）：窗口/排班/能力数据经本切片 HTTP 写命令产生（真实登录主账号+真实 admission 门禁+真实幂等）。
- **SQL 播种**（仍需）：`merchant_staff` 员工行（MER 员工六接口未交付，SCHW-D1 拆分结论）与 `schedule_reservation` 占用行（生产者 SCH-003 未交付）——测试文档显式标注播种边界，不冒充真实录入链路。
- **既有读链复跑**：SCH-001 W2-SCH-001～007 与（若已交付）SCH-002 W2-SCH2-001～008 全部复跑不回退。

## 1. 验收条目

| ID | Owner Issue | 触发与预期 |
|---|---|---|
| W2-SCHW-001 | SCH-004 | **窗口命令全路径与状态机**：POST 开窗 201（status=OPEN,version=0）→ PUT 编辑（时间/kind/容量全量替换，version+1）→ close（OPEN→CLOSED）→ open（CLOSED→OPEN）；非法转移（对 CLOSED 再 close）与 expectedVersion CAS 失配 → 409 `COMMON_CONFLICT`；`DELETE` 路由不注册（请求 404/405，勘误节口径）；每命令落 `schedule_write_action` 审计行（操作人/动作/目标/requestId） |
| W2-SCHW-002 | SCH-004 | **window_kind 矩阵（SCHW-D2）**：IN_STORE 服务开 GENERAL 窗成功、开 PICKUP/RETURN → 400（details 指 windowKind×fulfillmentType）；PICKUP_DELIVERY 服务开 PICKUP/RETURN 成功、开 GENERAL → 400；存量行（SQL 改 window_kind 或迁移前数据）回填 GENERAL 后 C 端查询可见；C 端 `?kind=PICKUP` 精确过滤、items 携带 kind 字段；**无 kind 参数时响应与 SCH-001 已批逐字段一致**（W2-SCHW-011 回归主断言） |
| W2-SCHW-003 | SCH-004 | **重叠策略（SCHW-D3）**：同 (store,service,kind) OPEN 窗口时间相交（含边界相接不相交的临界：[9,12)+[12,18) 放行）→ 409 `SCHEDULE_WINDOW_OVERLAP`；不同 kind 重叠放行；与 CLOSED 窗口重叠放行（重开时再拦）；PUT 移动窗口致重叠同拦；跨天多日窗口（寄养 3 天）合法；排班侧同 staff AVAILABLE 重叠同码拦截 |
| W2-SCHW-004 | SCH-004 | **占用守卫（SCHW-D4）**：SQL 播种 TEMP_LOCKED/CONFIRMED 预约行与窗口重叠 → close 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED`、容量下调 409、时间变更（收窄/平移）409；容量上调放行、CLOSED→OPEN 放行；播种 RELEASED/EXPIRED 行 → 不构成占用全放行；409 message/审计不产生状态变更行 |
| W2-SCHW-005 | SCH-004 | **临时停业批量关窗（SCHW-D5）**：多服务门店 batch-close [fromDate,toDate] → 范围内全服务 OPEN 窗口 CLOSED、`closedWindows` 全列；部分窗口被占用 → 进 `blockedWindows`（occupiedCount>0）、其余照关、单窗 close 仍 409；范围外窗口不动；同 requestId 重放返回原回执不重复执行；`fromDate>toDate`/跨度非法 400；审计 BATCH_CLOSE 多行共享 requestId |
| W2-SCHW-006 | SCH-004 | **排班写入与门禁（SCHW-D6）**：SQL 播种 ACTIVE 员工 → CRUD/close/open 全路径（AVAILABLE↔CLOSED，无 kind 字段、无周模板字段拒绝未知参数）；INACTIVE 员工写入 → 409；员工他店/不存在 → 404 防枚举（三归属一致校验）；排班关闭**无占用守卫**（播种重叠 CONFIRMED 预约仍可关——容量动态重算语义）；无 10 分钟锁定类行为（锁定归 SCH-003，反证：排期表无 lock 列变更） |
| W2-SCHW-007 | SCH-004 | **能力替换（SCHW-D7）**：PUT 全量替换——新增集落 ENABLED 行、移除集停用、保留集不动（uk 幂等）；serviceIds 含非本店/不存在服务 → 400 details 指明；重复 ID → 400；>200 项 → 400；INACTIVE 员工 → 409；GET 返回 ENABLED 集合；审计 REPLACE 行 |
| W2-SCHW-008 | SCH-004 | **幂等与并发（23 号）**：同 requestId 同参重放返回原回执（201 首次/200 重放）不重复落行；同 requestId 异参 → 409 `IDEMPOTENCY_KEY_CONFLICT`（首次失败后亦然）；并发同窗 close 争锁忙 → 409 `COMMON_CONFLICT` data:null；重放前重验会话/归属（撤权后不泄露旧回执）；批量关窗重放回执含原 blocked 明细 |
| W2-SCHW-009 | SCH-004 | **权限与会话反例矩阵**：无 Bearer/无效 Bearer → 401；非归属主账号（他店 merchantId/storeId）→ 404 同响应防枚举；admission 四态失败（未签/未批/门店 OFFLINE/商家停用，SQL 构造）→ 409 `SERVICE_STATE_NOT_ALLOWED`；admission 事实源故障 → 503；子账号（STAFF 成员）写入 → 403（V1 无成员绑定，理论反例 P3 登记，构造条件随 AUTH/MER 成员切片交付后补）；C 端会话访问商家写路由 → 401/403 |
| W2-SCHW-010 | SCH-004 | **商家端工作台读**：GET 窗口列表全状态（OPEN+CLOSED 同现）、kind/status/serviceId 过滤、日期范围分页（默认/边界）；GET 排班列表同口径；非归属查询 404；版本号与写入后 version 一致；`Cache-Control: no-store` |
| W2-SCHW-011 | SCH-004 | **C 端读侧回归与 kind 增列**：SCH-001 W2-SCH-001～007 全量复跑不回退（聚合/错误两分/校验会话/双履约投影/时间边界/容量/缺席失败关闭——按 SCH-002 交付状态取提供器在场/缺席两轨）；kind 增列断言（W2-SCHW-002）不破坏既有 JSON 消费（新增字段可选） |
| W2-SCHW-012 | SCH-004 | **架构守卫**：ARCH001～005——pet-schedule-biz 依赖白名单不变（schedule-api/service-api/merchant-api/common/event-api/task-core/redis/mybatis）；不跨模块 Repository（merchant_staff 经 pet-merchant-api、service_item 经 pet-service-api）；biz 不依赖 biz；`pet.schedule.command.enabled` 默认关闭时写路由不装配（DisabledTest 口径，对齐先例） |
| W2-SCHW-013 | SCH-004 | **写入→查询模块链证据（E2E 边界披露）**：真实写入链开窗（PICKUP_DELIVERY 服务 PICKUP/RETURN 各一）+能力/排班写入+SQL 播种员工行 → C 端查询（提供器在场，SCH-002 已交付时真装配；否则种子替身构造）items 含两窗且 kind 正确、min 公式生效；**声明边界**：员工行 SQL 播种（MER 员工切片未交付）、occupiedCount 无生产者恒 0、"商家录入人员→排班→可约"完整 E2E 待 MER 员工+SCH-002+SCH-003 齐备后另行验收（SCH-D3 披露延续） |

## 2. 测试形态与执行条件（实现切片时）

- **HTTP 集成测试**（真实 MySQL/Redis+真实登录链，先例 `ScheduleAvailabilityHttpTest`/`MerchantServiceController` 链）：
  - 夹具：`CAuthHttpTest.HttpFixture` 自举 Schema 06/14/25+26/28/29/33 号商家链；服务经写入方 HTTP 产生并 APPROVE 至 ACTIVE（ADM-001 已合入先例）；商家/门店经准入链构造可经营事实。
  - 播种边界（显式标注）：merchant_staff 员工行、schedule_reservation 占用行、（如需）迁移前存量窗口行——三项无真实生产者/写入方（MER 员工/SCH-003）。
  - 反例构造：SQL 直改 employment_status/admission 四态、窗口/排班重叠组合、预约行状态四值矩阵。
- **模块测试**：命令服务状态机/守卫/重叠判定单测（隔离 MySQL）；审计表约束（uk(request_id,target_id) 批量多行）；34 号 DDL+V29 迁移在空库与 33 号后库两形态执行。
- **门控与环境**：随机端口独立 boot 实例+`pet.schedule.command.enabled=true`（生产默认关闭）；MySQL 33452 临时实例/Redis 16383（docker，`env -u DOCKER_HOST`）；并行避让：wmic 检查 pet-boot JVM 端口占用；模块单测+boot 集成+ARCH 守卫随 CI。
- **不声明**：不声称"商家维护排期→消费者预约成功"完整 E2E（SCH-003/TX-001 未交付）；不声称运营监管能力（SCHW-D8 延后）；不声称员工管理（MER 域）；不声称改期/取消（订单域）。

## 3. 覆盖矩阵（用例 ↔ 需求）

| 用例组 | PRD/契约依据 |
|---|---|
| 命令全路径/状态机（W2-SCHW-001） | PRD §5.4「手动开放或关闭时段」:553、§6.2 可约↔已关闭；SCHW-D3 |
| window_kind（W2-SCHW-002） | PRD §5.4 字段表上门时段/送回时段 :604-610、履约模型 :549-554；SCH-D4 裁决；SCHW-D2 |
| 重叠（W2-SCHW-003） | SCHW-D3（无 PRD 明文，契约新增规则——裁决后定稿） |
| 占用守卫（W2-SCHW-004） | PRD §5.4 :560/:571「已有订单占用的时段不得关闭或降容量」「临时停业…拦截并提示先处理订单」；SCHW-D4 |
| 临时停业（W2-SCHW-005） | PRD §5.4 :553/:571；SCHW-D5 |
| 排班（W2-SCHW-006） | PRD §5.6 :797/:808/:833；§6.2 末行动态容量；SCHW-D6；SCH2-D1 两口径兼容 |
| 能力（W2-SCHW-007） | PRD §5.6 :869-874（表粒度偏差按 SCH2-D3 登记）；SCHW-D7 |
| 幂等/并发（W2-SCHW-008） | 23 号 §3～§7；27 号 §7 执行序 |
| 权限（W2-SCHW-009） | 27 号 §3/§5；SVCW-D5 getAdmission 先例；运营端 §3.3 边界（运营零写入面——无 admin 写路由为本切片断言之一） |
| 工作台读（W2-SCHW-010） | SCH-D10「商家端日历细分状态归 SCH-004」；10 号 §4.8/§4.9 GET |
| 读侧回归（W2-SCHW-011） | SCH-D1～D11 全部已批语义；SCHW-D2 附则 |
| 架构（W2-SCHW-012） | AGENTS 架构禁令；ARCH001～005 |
| 链路证据（W2-SCHW-013） | SCH-D3 披露框架；SCH-D7「真实开窗→查询→预约验收前必须交付写入方」 |

## 4. 明确不测/不实现（本轮边界）

- hold/confirm/release/swap/TEMP_LOCKED/10 分钟锁定与超时释放、订单人员指派（SCH-003+订单域；07 号 §6.2 一字不动）。
- 员工六接口 HTTP（MER 域实现切片；disable 在途守卫维持 IMPLEMENTATION_BLOCKED）。
- 运营只读监管路由与利用率/锁定分布（SCHW-D8 延后——SCH-003 占用生产者交付后再裁）。
- 批量 open（一键恢复营业）、临时停业时间裁切、窗口物理删除（SCHW-D3/D5 附则登记未决）。
- 周模板/循环排期字段（10 号 §4.8 禁用项，出现即 400——作为未知参数拒绝断言纳入 W2-SCHW-001）。
- 改期/取消对窗口的影响（订单域动作，写入方零触碰——守卫只读不写 reservation）。
- 真机/模拟器窗口级 E2E、M-002 页面联调（前端切片验收）。
