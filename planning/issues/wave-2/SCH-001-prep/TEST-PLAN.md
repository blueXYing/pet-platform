# SCH-001 排期可用性查询切片 — 测试计划（v0.1 草案，随裁决修订）

日期：2026-09-23。分支 `codex/sch001-prep-20260923`。
前置：CCR [schedule-availability-proposal.md v0.1](../../../ccr/CCR-W2-API-001/schedule-availability-proposal.md) **尚未获批**——本计划按推荐口径（SCH-D1～D11）起草，人工裁决改变口径时逐条修订后再执行；实现切片交付时逐项报告 PASS/FAIL/NOT_EXECUTED 及所用规范、数据库、应用提交。
编号风格对齐 [WAVE_2_TEST_ACCEPTANCE.md](../../WAVE_2_TEST_ACCEPTANCE.md)。**新编号 W2-SCH-001～006 为本切片验收条目，登记进 WAVE_2_TEST_ACCEPTANCE.md 由该文件唯一 Writer 执行，本切片不改该共享文件。**

## 0. 阶段语义锁定（防漂移基线，SCH-D3/D6）

- **无写入方**：`schedule_availability_window` 现无任何写路径，全部窗口数据经 SQL 夹具播种（SVC-D4 先例：`ServiceQueryHttpTest` 26/28/29/33 号 Schema 加载+INSERT 播种）；本切片测试**不得**声称"商家开窗→消费者查到"完整链路验收通过，ORD-006/007 仅报模块证据。
- **阶段容量**：`effectiveCapacity=configuredCapacity`、`occupiedCount=0`、`remainingCapacity=effectiveCapacity`（SCH-002 获批升级 min 公式时，W2-SCH-006 作为回归基线复跑并按新契约修订）。
- **不测不实现**：hold/confirm/release/swap（SCH-003）、人员容量联动（SCH-002）、120 分钟间隔与跨天送回约束的**服务端校验**（查询侧不实现；C 端置灰联动属前端，不在本计划）、排期写入命令/页面、运营监管路由。

## 1. 验收条目

| ID | Owner Issue | 触发与预期 |
|---|---|---|
| W2-SCH-001 | SCH-001 | **窗口查询聚合**：SQL 播种某可见服务多窗口（同日多段/跨日/跨天寄养长窗口/CLOSED 混合）→ 200 items 仅含 OPEN 窗口，字段 start/end/effectiveCapacity/occupiedCount/remainingCapacity/available 齐全，分钟级区间原样返回（如 09:15～10:45），按 start 升序；跨天窗口与查询区间部分相交时**整体返回不切割**；响应不携带 windowId/version/status/configuredCapacity 细分 |
| W2-SCH-002 | SCH-001 | **可见性与错误两分（不混同）**：服务不存在/OFFLINE/DRAFT/REVIEWING/REJECTED（SQL 直改 status）、商家 OFFLINE、门店 FROZEN、不接新单（申请/签约事实破坏）→ 404 body code=`SERVICE_NOT_FOUND`（状态码+code 双断言，SVC 勘误先例）；窗口表读取失败/播种未知 status 值 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`（不降级空页）；服务可见但范围内无 OPEN 窗口（全 CLOSED/未开窗=临时停业等价）→ 200 空 items（与 404/503 严格三区分） |
| W2-SCH-003 | SCH-001 | **字段/校验/会话**：serviceId/storeId 雪花 String（>2^53 大 ID 往返不丢精度）；日期非法格式/endDate<startDate/跨度 32 天/缺必填 → 400 `COMMON_INVALID_ARGUMENT`（details 指明字段）；无 Authorization → 401 `COMMON_UNAUTHORIZED`；storeId 与服务归属不一致 → 404 `SERVICE_NOT_FOUND`（SCH-D9，防探测与不可见同响应）；携带无效/过期 Bearer → 401 |
| W2-SCH-004 | SCH-001 | **履约方式投影**：IN_STORE 与 PICKUP_DELIVERY 两服务各播种窗口 → 返回结构一致、窗口集不分 kind（无上门/送回标记字段）；响应不携带 fulfillmentType；120 分钟/跨天约束不出现在查询侧（断言：构造送回窗口早于上门+120min 的数据，查询仍正常返回该窗口——约束归 SCH-003 服务端校验） |
| W2-SCH-005 | SCH-001 | **时间边界**：已结束窗口（end_at<=now）不返回；进行中窗口（start<=now<end）返回且 available=false；查询日期按 Asia/Shanghai 解释为 [当日00:00,次日00:00)（构造 UTC 边界用例：北京时间 23:30 窗口归属正确日期）；CLOSED 窗口不进 items；跨 31 天边界（恰好 31 天=200，32 天=400） |
| W2-SCH-006 | SCH-001 | **阶段容量语义基线（SCH-D6）**：播种 configuredCapacity=1/5/N 多档 → effectiveCapacity 逐一=configuredCapacity、occupiedCount=0、remainingCapacity=同值、available=true（OPEN 未过滤）；进行中窗口 available=false 与容量无关；断言响应无 qualifiedAvailableStaffCount 字段泄漏（内部占位不外显）。SCH-002 升级后此条按新契约改造为 min 公式回归 |

对应既有条目：ARCH001～005 持续通过（pet-schedule-biz 仅依赖 pet-schedule-api/pet-service-api/pet-merchant-api/pet-common/pet-event-api/pet-task-core，不跨模块 Repository，biz 不依赖 biz）；W2-SVC-001～003（服务读侧）与 W2-SVCW 系列（写入方）不回退——本切片消费 checkBookable/getServiceSnapshot，其反例场景需复跑不破坏。

## 2. 测试形态与执行条件（实现切片时）

- **HTTP 集成测试**（真实 MySQL/Redis + 真实登录链，先例 `ServiceQueryHttpTest`）：
  - 夹具：真实申请→审批 APPROVED→签署链产生可经营商家/门店/主账号会话；服务经**写入方 HTTP**产生并 APPROVE 至 ACTIVE（ADM-001 写入方已合入，不再需要 SQL 播种 service_item——与服务域当时不同，如实区分）；**窗口数据经 SQL 播种 `06-核心数据库Schema-v0.1.sql` 的 schedule_availability_window**（无写入方，SCH-D3 披露主项）。
  - 反例构造：SQL 直改 service_item.status（五值反例）、merchant/merchant_store/application/signing 状态破坏（四条件反例沿用 SVC 夹具先例）、窗口行 status 播种未知值（503 路径）。
  - 会话：登录链产生 MINIAPP Bearer（401 反例=无头/坏 token）。
- **门控与环境**：随机端口独立 boot 实例 + `pet.schedule.query.enabled` 类开关（默认关闭，先例 pet.service.query.enabled）；MySQL 33452/Redis 16383 共享环境，跑前检查并发；模块单测 + boot 集成 + ARCH 守卫随 CI。
- **不声明**：不把本切片通过声称为完整"发布→看到→预约"E2E（预约在 SCH-003/TX-001）；不声明临时停业/商家排期管理可用（写入方未交付）。

## 3. 覆盖矩阵（用例 ↔ 需求）

| 用例组 | PRD/契约依据 |
|---|---|
| 分钟级窗口/跨天整体/升序（W2-SCH-001） | SSOT §12.1（分钟级、无固定 60 分钟槽、无周模板）；商家端 §5.4（跨天占用区块、日历展示）；HTTP10 §3.4 四规则 |
| 错误两分（W2-SCH-002） | SVC-D1b（404 防探测不区分原因）；10 号 §3.3.1 失败关闭 503 先例；§3.4 |
| 会话（W2-SCH-003） | SCH-D1（登录态，PRD C 端 §5.1.13/§5.1.15）；23 号 §3（读无 requestId） |
| 双时段呈现（W2-SCH-004） | SCH-D4；商家端 §5.4 履约模型/字段表（上门时段/送回时段）；C 端 §5.1.15（置灰联动在前端） |
| 时间边界/时区/跨度（W2-SCH-005） | SCH-D2；23 号 §2（Asia/Shanghai、亚毫秒拒绝）；10 号 §2.6 |
| 阶段容量（W2-SCH-006） | SCH-D6；Issue Catalog SCH-001/SCH-002 拆分行（min 公式归 SCH-002）；SSOT §12.2 |
| 可见性四条件 | SSOT §一/§十九；SVC-D1/D1b 已批 |

## 4. 明确不测/不实现（本轮边界）

- hold/confirm/release/swap/TEMP_LOCKED 过期释放（SCH-003；PLAT-004 任务化）。
- effectiveCapacity=min(configured,availableStaff) 与人员可用性（SCH-002；商家域第六查询缺口已在 SCOPE-VERIFY S4.1 登记）。
- 120 分钟间隔与跨天送回的**服务端校验**（SCH-003 hold/订单侧；本切片只保证查询不因此过滤窗口）。
- 排期写入命令（§4.8）、人员时间写入（§4.9）、临时停业拦截、运营只读监管路由（写入方切片 SCH-D7）。
- 售罄口径（B5 未决，维持不实现）、最短提前预约时长（SCH-D5，PRD 无规则不造规则）。
- 订单创建/支付/改期/退款全链路（TX/PAY/REF 域）。
