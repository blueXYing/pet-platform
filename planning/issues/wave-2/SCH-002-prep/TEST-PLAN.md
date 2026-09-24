> 2026-09-24 裁决更新：D1 已选包含完整排班覆盖；以 v0.2 及联合回执为准，不再测试“不消费排班”作为有效产品选项。测试仍未执行。

# SCH-002 容量人员事实源切片 — 测试计划（v0.1 DRAFT，随裁决修订）

日期：2026-09-24。分支 `codex/sch002-prep-20260924`。
前置：CCR 草案 [schedule-capacity-proposal.md v0.1（SCH2-D1～D7，**DRAFT 未获批**）](../../../ccr/CCR-W2-API-001/schedule-capacity-proposal.md)；实现切片交付时逐项报告 PASS/FAIL/NOT_EXECUTED 及所用规范、数据库、应用提交。
编号风格对齐 [WAVE_2_TEST_ACCEPTANCE.md](../../WAVE_2_TEST_ACCEPTANCE.md)。**新编号 W2-SCH2-001～008 为本切片验收条目，登记进 WAVE_2_TEST_ACCEPTANCE.md 由该文件唯一 Writer 执行，本切片不改该共享文件。** SCH2-D1（排班口径）裁决后本计划对应条目定稿——W2-SCH2-004 预置两种口径的写法，按裁决取一。

## 0. 双轨口径说明（延续 SCH-001 D6 框架）

- **真实装配**（本切片新增）：boot 注册真实提供器（第六查询真实现 + SCH 域能力/排班真 Mapper），员工/能力/排班事实以 **SQL 播种**（三表均无写入方，SCH-D3 披露同款——员工六接口已批未实现、能力/排班归 SCH-004/M-002）；断言基于真实数据路径。
- **提供器缺席构造**（保留）：直接构造 staffFacts=null 的实现，断言失败关闭 503（SCH-001 W2-SCH-007 口径，防御分支不删）。
- **种子替身 bean**（收缩）：`ScheduleAvailabilityHttpTest.schStaffFacts`（:79-80,204-207）按 SCH2-D6 收缩——聚合断言迁移到真实提供器路径，替身仅保留给缺席/固定计数的构造场景或删除（实现切片定，如实登记）。

## 1. 验收条目

| ID | Owner Issue | 触发与预期 |
|---|---|---|
| W2-SCH2-001 | SCH-002 | **真实装配人员事实接通（SCH2-D5）**：boot 上下文注册真实提供器（真实 DataSource+真实 checkBookable 链+第六查询真实现），SQL 播种窗口（configured=5）+在职在岗有能力（+排班，随 D1）3 人 → 200，`effectiveCapacity=3`、`remainingCapacity=3`、`available=true`；同装配无播种行 → 200 空 items（窗口无行）或有窗口无人员 → `effectiveCapacity=0`、`available=false`——**可见服务恒 503 的 SCH-001 有意状态解除**（与 W2-SCH-007 缺席构造对照断言，证明 503 消除源于提供器在场而非数据差异） |
| W2-SCH2-002 | SCH-002 | **人员四条件过滤（SCH2-D1）**：同一门店播种员工矩阵——在职在岗有能力（计入）；`employment_status=INACTIVE` 离职（不计）；`service_enabled=0` 停用/停排（不计）；他店在职在岗员工（不计，归属过滤）；在职在岗但无能力行（不计，W2-SCH2-003 覆盖）→ 计数仅含满足全部条件者，min 公式按该计数生效 |
| W2-SCH2-003 | SCH-002 | **能力表语义（SCH2-D3）**：`staff_service_capability` ENABLED 行计入；同 staff 同 service 第二行（uk 冲突不可播种，改插他 service 行不误计入）；staff 不在门店名单的悬挂 ENABLED 行（交集排除）；**status 播种未知值（如 'FOO'）→ 503 COMMON_DEPENDENCY_UNAVAILABLE**（不静默排除、不降级 available=true）；能力表全空+员工在场 → 计数 0、`available=false`（正常事实非 503） |
| W2-SCH2-004 | SCH-002 | **排班消费（随 SCH2-D1 裁决定稿）**——口径①（推荐，排班纳入）：AVAILABLE 单窗完全覆盖 [from,to) 计入；多段 AVAILABLE 并集覆盖（上午+下午两段班）计入；部分重叠（窗口 09:00-18:00 vs 排班 10:00-18:00）不计入；CLOSED 段不构成覆盖；无排班行=0 → `effectiveCapacity=0`、`available=false`；排班 status 未知值 → 503；可用性随窗口变化（同 staff 同日两窗口一有一无）。口径②（排班不消费）：播种排班行不改变计数（回归锁）；排班表未知 status 不触发 503（不读取） |
| W2-SCH2-005 | SCH-002 | **事实源故障失败关闭（SCH2-D4）**：merchant_staff 播种未知 `employment_status` → 503（整页失败关闭，不降级空 items/伪容量）；员工事实读取失败（构造读异常）→ 503；提供器返回负数 → 503；提供器缺席构造（staffFacts=null）→ 可见服务 503、不可见服务先判 404（W2-SCH-007 口径复跑）；第六查询 NOT_FOUND（门店行删除的跨快照漂移构造）→ 404 `SERVICE_NOT_FOUND` 投影 |
| W2-SCH2-006 | SCH-002 | **min 公式与容量边界（SSOT §12.2，ORD-004 模块证据）**：configured=5/计数=3 → 3；计数=0 → 0/`available=false`；计数=7>configured=5 → 5；configured=1/计数=3 → 1；SQL 种子 schedule_reservation（TEMP_LOCKED 重叠行）→ `occupiedCount=1`、remaining=e-1（权威表占用计数不回退，W2-SCH-006 真实路径复证）；RELEASED/EXPIRED 不计数；进行中窗口 `available=false` 与容量无关（复跑） |
| W2-SCH2-007 | SCH-002 | **第六查询模块契约（merchant-biz 模块级）**：`listActiveStoreStaffFacts`——storeId 过滤正确；无所有者前提（不校验 owner，匿名 context 可调）；确认门店不存在 → NOT_FOUND；门店存在无员工 → 空列表（正常 0，非错误非 503）；未知 employment_status → 503；大 ID（>2^53）String 往返；DTO 不可变、staffId 数值升序；**不读 SCH 域表**（能力/排班行存在与否不影响本查询结果） |
| W2-SCH2-008 | SCH-002 | **既有用例与架构守卫不回退（SCH2-D6）**：W2-SCH-001～007 复跑全绿（聚合/错误两分/校验会话/履约投影/时间边界/容量/缺席失败关闭）；`ScheduleQueryDisabledTest` 开关默认关不装配不变；ARCH001～005——pet-schedule-biz 依赖不变（schedule-api/service-api/merchant-api/common/event-api/task-core），merchant-biz 不读 SCH 表、schedule-biz 不读 merchant 表、biz 不依赖 biz |

## 2. 测试形态与执行条件（实现切片时）

- **HTTP 集成测试**（真实 MySQL/Redis + 真实登录链，先例 `ScheduleAvailabilityHttpTest`）：
  - 夹具：`CAuthHttpTest.HttpFixture` 自举 Schema 06/14/25 + 26/28/29/33 号商家链；服务经写入方 HTTP 产生并 APPROVE 至 ACTIVE（ADM-001 已合入）；**窗口/预约/员工/能力/排班五类数据一律 SQL 播种**（写入方均未交付——SCH-D3 披露主项，测试文档显式标注，不冒充真实录入链路）。
  - 员工播种注意：`merchant_staff.merchant_id` 必须取门店真实归属（selectOwnedStaff 同款 join 语义），避免悬挂；`staff_service_capability`/`staff_availability_window` 按目标 serviceId/storeId 关联。
  - 反例构造：SQL 直改 employment_status/service_enabled/capability status/排班 status（未知值）、删除门店行（NOT_FOUND 漂移构造）、种子预约行（占用计数）。
- **merchant-biz 模块测试**（W2-SCH2-007，先例 MySqlMerchantDomainTestDatabase）：隔离 MySQL 上直测第六查询；大 ID/不可变 DTO 序列化断言（先例 MerchantDtoSerializationTest）。
- **门控与环境**：随机端口独立 boot 实例 + `pet.schedule.query.enabled=true`（生产默认关闭）；MySQL 33452 临时实例 / Redis 16383（docker，`env -u DOCKER_HOST`）；并行避让：wmic 检查 pet-boot JVM 端口占用；模块单测 + boot 集成 + ARCH 守卫随 CI。
- **不声明**：不声称"商家录入人员→容量生效"真实 E2E（员工/能力/排班写入方未交付，SQL 播种仅模块证据）；不声称 ORD-005 超卖拒绝/CON-001/002 并发结论（hold 侧 SCH-003/QA-002）；不声称完整可约判断面向用户启用（开关默认关，写入方未交付）。

## 3. 覆盖矩阵（用例 ↔ 需求）

| 用例组 | PRD/契约依据 |
|---|---|
| 真实装配接通（W2-SCH2-001/005） | SCH-D6 裁决（启用完整可约判断须等 SCH-002 接通；缺席/故障仍失败关闭）；SCH2-D4/D5 |
| 人员过滤（W2-SCH2-002） | 商家端 PRD §5.6「仅在职且在岗的人员可被商家指派」（:801）；字段表在职/在岗（:845-856）；SSOT §13 |
| 能力语义（W2-SCH2-003） | §5.6 可服务类目「影响预约可选人员」（:869-874，按表粒度）；SCH2-D3 |
| 排班消费（W2-SCH2-004） | §5.6 排班管理/人员可约时间「与门店可预约时段联动」（:797/:808）；SCH2-D1（裁决口径①/②二选一） |
| min 公式（W2-SCH2-006） | SSOT §12.2；ORD-004（docs/07-testing/14号:47「配置容量5；合格在岗人员3→effectiveCapacity=3」） |
| 第六查询契约（W2-SCH2-007） | SVC-D5/STR-D6 无所有者前提先例；27号 §3 错误两分；SCH2-D2 |
| 不回退（W2-SCH2-008） | SCH-D1～D11 已批语义全部维持；ARCH001～005 |

## 4. 明确不测/不实现（本轮边界）

- hold/confirm/release/swap/TEMP_LOCKED 生命周期与并发（SCH-003；CON-001/002 归其验收+QA-002）。
- 员工六接口 HTTP（POST/PUT/enable/disable）——已批未实现，不随本切片实现；disable 的在途订单守卫维持 IMPLEMENTATION_BLOCKED。
- 能力/排班写入命令（§4.9）、window_kind、临时停业拦截、运营只读监管路由（SCH-004/M-002）。
- 真实"商家开窗+录入人员→消费者查到可约"链路验收（写入方未交付，仅模块证据，SCH-D3 披露延续）。
- 订单创建/支付/改期/退款全链路（TX/PAY/REF 域）；真机/模拟器窗口级 E2E。
- 若 SCH2-D1 裁口径③（排班缺失 503）：W2-SCH2-004 改为"排班缺失 503 失败关闭"断言组，其余条目不受影响（本计划按口径①/② 预置，裁决后定稿）。
