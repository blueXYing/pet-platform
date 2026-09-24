# SCH-001 排期可用性查询切片 — 测试计划（v0.2，随 2026-09-23 裁决修订）

日期：2026-09-23。分支 `codex/sch001-prep-20260923`。
前置：CCR [schedule-availability-proposal.md v0.2](../../../ccr/CCR-W2-API-001/schedule-availability-proposal.md) 已批准（[决定回执](../../../ccr/CCR-W2-API-001/schedule-availability-decisions.md)，SCH-D1～D11，**D6 为裁决修改版**）；实现切片交付时逐项报告 PASS/FAIL/NOT_EXECUTED 及所用规范、数据库、应用提交。
编号风格对齐 [WAVE_2_TEST_ACCEPTANCE.md](../../WAVE_2_TEST_ACCEPTANCE.md)。**新编号 W2-SCH-001～007 为本切片验收条目，登记进 WAVE_2_TEST_ACCEPTANCE.md 由该文件唯一 Writer 执行，本切片不改该共享文件。**

## 0. v0.2 修订说明（D6 失败关闭口径）

- **种子模式与真实装配双轨**（裁决核心）：SQL 种子+人员计数测试替身**仅限模块测试装配**；真实装配不提供人员事实提供器，可见服务的 availability 查询必须失败关闭 503 `COMMON_DEPENDENCY_UNAVAILABLE`，不得降级 `available=true`/伪容量。
- **容量公式在种子模式全量验证**：`effectiveCapacity=min(configuredCapacity, qualifiedAvailableStaffCount)`（SSOT §12.2）；`occupiedCount` 读取 `schedule_reservation` 权威表（TEMP_LOCKED/CONFIRMED 重叠计数，测试以 SQL 种子预约行驱动，非占位常量）。
- 新增 **W2-SCH-007 真实装配失败关闭**；W2-SCH-006 由"阶段容量语义"改为"种子模式容量聚合（min 公式+权威表占用）"。

## 1. 验收条目

| ID | Owner Issue | 触发与预期 |
|---|---|---|
| W2-SCH-001 | SCH-001 | **窗口查询聚合（种子模式）**：SQL 播种某可见服务多窗口（同日多段/跨日/跨天寄养长窗口/CLOSED 混合）→ 200 items 仅含 OPEN 窗口，字段 start/end/effectiveCapacity/occupiedCount/remainingCapacity/available 齐全，分钟级区间原样返回，按 start 升序；跨天窗口与查询区间部分相交时**整体返回不切割**；响应不携带 windowId/version/status/configuredCapacity/qualifiedAvailableStaffCount 细分 |
| W2-SCH-002 | SCH-001 | **可见性与错误两分（不混同）**：服务不存在/OFFLINE/DRAFT/REVIEWING/REJECTED（SQL 直改 status）、商家 OFFLINE、门店 FROZEN、不接新单 → 404 body code=`SERVICE_NOT_FOUND`（状态码+code 双断言）；服务/商家事实读取失败或播种未知状态 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`（不降级空页）；可见但范围内无 OPEN 窗口 → 200 空 items（种子模式；三区分：404/503/200 空） |
| W2-SCH-003 | SCH-001 | **字段/校验/会话**：serviceId/storeId 雪花 String（>2^53 大 ID 往返）；日期非法格式/endDate<startDate/跨度 32 天/缺必填/未知 query 参数 → 400 `COMMON_INVALID_ARGUMENT`（details 指明字段）；无 Authorization → 401 `COMMON_UNAUTHORIZED`（SCH-D1 登录态，路由不属 STR-D8 四路由）；携带无效/过期 Bearer → 401；storeId 与服务归属不一致 → 404 `SERVICE_NOT_FOUND`（SCH-D9，与不可见同响应） |
| W2-SCH-004 | SCH-001 | **履约方式投影（SCH-D4）**：IN_STORE 与 PICKUP_DELIVERY 两服务各播种窗口 → 返回结构一致、窗口集不分 kind（无上门/送回标记字段）；响应不携带 fulfillmentType；120 分钟/跨天约束不在查询侧（构造送回窗口早于上门+120min 的数据，查询仍正常返回该窗口——服务端约束归 SCH-003） |
| W2-SCH-005 | SCH-001 | **时间边界**：已结束窗口（end_at<=now）不返回；进行中窗口（start<=now<end）返回且 available=false；查询日期按 Asia/Shanghai 解释为 [当日00:00,次日00:00)（UTC 边界用例：北京时间 23:30 窗口归属正确日期）；CLOSED 窗口不进 items；跨度边界（31 天=200，32 天=400） |
| W2-SCH-006 | SCH-001 | **容量聚合（种子模式，min 公式+权威表占用）**：人员计数替身=3、窗口 configuredCapacity=5 → effectiveCapacity=3、remainingCapacity=3、available=true；configuredCapacity=1 → effectiveCapacity=1（min 生效）；替身=0 → effectiveCapacity=0、available=false；SQL 种子 schedule_reservation（TEMP_LOCKED 重叠行）→ occupiedCount=1、remainingCapacity=e-1；RELEASED/EXPIRED 行不计数；进行中窗口 available=false 与容量无关 |
| W2-SCH-007 | SCH-001 | **真实装配失败关闭（D6 裁决核心，与种子模式区分）**：同一真实 MySQL 装配下构造**无人员事实提供器**的 ScheduleQueryApiImpl（真实 DataSource+真实 checkBookable 链、staffFacts=null）：不可见服务 → 404 `SERVICE_NOT_FOUND`（可见性先判，人员缺失不掩盖不可见）；可见服务 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`；**绝不返回 available=true 或 configuredCapacity 冒充的伪容量**；boot 上下文内默认装配（提供器由测试注册的种子替身在场）同服务同窗口 200——证明 503 源于提供器缺席而非数据；开关 `pet.schedule.query.enabled` 默认关闭时路由不可达（403/未装配） |

对应既有条目：ARCH001～005 持续通过（pet-schedule-biz 仅依赖 pet-schedule-api/pet-service-api/pet-merchant-api/pet-common/pet-event-api/pet-task-core，不跨模块 Repository，biz 不依赖 biz）；W2-SVC-001～003（服务读侧）与 W2-SVCW 系列（写入方）不回退——本切片消费 checkBookable，其反例场景复跑不破坏。

## 2. 测试形态与执行条件（实现切片时）

- **HTTP 集成测试**（真实 MySQL/Redis + 真实登录链，先例 `ServiceQueryHttpTest`）：
  - 夹具：`CAuthHttpTest.HttpFixture`（自举 Schema 06/14/25，含 schedule_availability_window/schedule_reservation）+ 26/28/29/33 号（商家链）；真实申请→审批→签署链产生可经营商家/门店/主账号会话；服务经**写入方 HTTP**产生并 APPROVE 至 ACTIVE（ADM-001 已合入——如实区分：服务数据不再 SQL 播种，窗口/预约数据仍 SQL 播种，SCH-D3 披露主项）。
  - **种子模式人员替身**：boot 上下文注册 `QualifiedStaffFactsPort` 测试 bean（固定计数，标注"模块测试种子事实"）；**真实装配失败关闭**用真实 DataSource 直接构造 staffFacts=null 的实现断言（W2-SCH-007），不注册替身的第二个完整 boot 省略（等效语义、更低成本，如实登记）。
  - 反例构造：SQL 直改 service_item.status（五值）、merchant/merchant_store 状态破坏、窗口 status 播种未知值、schedule_reservation 种子行。
- **门控与环境**：随机端口独立 boot 实例 + `pet.schedule.query.enabled=true`（生产默认关闭）；MySQL 33452 临时实例（mysqld 自举，datadir D:/Temp/coord-mysql-33452，用后保留或停并记录）/Redis 16383（docker，`env -u DOCKER_HOST`）；并行避让：wmic 检查 pet-boot JVM 端口占用；模块单测 + boot 集成 + ARCH 守卫随 CI。
- **不声明**：不把本切片通过声称为完整"发布→看到→预约"E2E（预约在 SCH-003/TX-001）；不声明可约事实已核实/完整可约判断启用（D6）；不声明临时停业/商家排期管理可用（SCH-004 未交付）。

## 3. 覆盖矩阵（用例 ↔ 需求）

| 用例组 | PRD/契约依据 |
|---|---|
| 分钟级窗口/跨天整体/升序（W2-SCH-001） | SSOT §12.1（分钟级、无固定 60 分钟槽、无周模板）；商家端 §5.4（跨天占用区块）；HTTP10 §3.4 四规则 |
| 错误两分（W2-SCH-002/007） | SVC-D1b（404 防探测）；SCH-D6 裁决（缺人员事实失败关闭 503，不降级）；10 号 §3.3.1 失败关闭先例 |
| 会话（W2-SCH-003） | SCH-D1（登录态，PRD C 端 §5.1.13/§5.1.15）；23 号 §3（读无 requestId） |
| 双时段呈现（W2-SCH-004） | SCH-D4 裁决（不分 kind；window_kind 延后写入方；不宣称双时段完成）；商家端 §5.4 履约模型/字段表；C 端 §5.1.15 |
| 时间边界/时区/跨度（W2-SCH-005） | SCH-D2；23 号 §2（Asia/Shanghai、亚毫秒拒绝）；10 号 §2.6 |
| 容量聚合（W2-SCH-006） | SCH-D6（min 公式+权威表占用，种子模式验证）；SSOT §12.2 |
| 真实装配失败关闭（W2-SCH-007） | SCH-D6 裁决原文（真实接口不得占位容量返回 available=true；缺事实失败关闭） |
| 可见性四条件 | SSOT §一/§十九；SVC-D1/D1b 已批 |

## 4. 明确不测/不实现（本轮边界）

- hold/confirm/release/swap/TEMP_LOCKED 过期释放（SCH-003；PLAT-004 任务化）。
- 人员可用性事实源（SCH-002：商家域第六查询+staff_availability_window/staff_service_capability 组合）——本切片仅端口形状+替身。
- 120 分钟间隔与跨天送回的**服务端校验**（SCH-003 hold/订单侧）。
- 排期写入命令（§4.8）、人员时间写入（§4.9）、临时停业拦截、window_kind、运营只读监管路由（SCH-004/M-002）。
- 售罄口径（B5 裁决维持不实现，available=false 表达无位可约）、最短提前预约时长（SCH-D5，PRD 无规则不造规则）。
- 订单创建/支付/改期/退款全链路（TX/PAY/REF 域）；真机/模拟器窗口级 E2E。
