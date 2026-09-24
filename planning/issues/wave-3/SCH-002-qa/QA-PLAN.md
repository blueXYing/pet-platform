# SCH-002 独立 QA 计划（2026-09-24）

契约冻结基线 `af76372`（PR #80 已合入 develop；含 07 号唯一 ENABLED 状态澄清）；只验证已批准的 SCH-002 读侧容量切片。依据 SSOT §12.2、§13、§29，07 号 §4.4、CCR-W2-API-001 排期人员容量提案 v0.2 与联合审阅回执，以及既有 SCH-002 TEST-PLAN。测试数据由 SQL 写入独立随机 MySQL 数据库；这不是员工、能力或排班录入链路的 E2E 证据。

## 独立反例矩阵

| 组 | 构造 | 必须观察到 |
|---|---|---|
| Q1 真实链 | 可见服务、OPEN 窗、MER 同店 ACTIVE 且 service_enabled=1、SCH 该 service_id ENABLED 能力与完整 AVAILABLE 排班 | HTTP 200；configured=5/合格 3 时 effective=3；无人员是 0 且 available=false；人员 7 时 effective 仍为 5 |
| Q2 半开并集 | 同人 [09:00,10:00)+[10:00,11:00) 覆盖 [09:00,11:00)；随后第二段改成 [10:01,11:00) | 前者计 1，后者计 0；只覆盖起点或终点附近不算完整覆盖 |
| Q3 交集与去重 | 多段排班/重叠历史行；他店人员及他店排班；离职、停岗、无该服务能力；同类目另一服务的 ENABLED 能力 | 仅同店同服务且四层资格全满足的每个 staff_id 计一次；悬挂能力与排班不能扩大 MER 员工名单 |
| Q4 失败两分 | MER 未知 employment_status/service_enabled 或命中目标 store_id 但 merchant_id 与门店权威 merchant_id 不符；SCH 能力或排班未知 status；相关读异常；缺 provider；已确认门店不存在；确定空事实 | 未知/事实损坏/读取故障/缺 provider 均 503 COMMON_DEPENDENCY_UNAVAILABLE；不存在 404 SERVICE_NOT_FOUND；空事实 200 且容量 0 |
| Q5 占用 | 同服务 TEMP_LOCKED/CONFIRMED 重叠，RELEASED/EXPIRED 对照 | occupied 与 remaining 延续 SCH-001 口径，不能把 effective 当作 remaining |
| Q6 装配 | 默认配置与显式启用配置 | 默认不开；开启后走真实 MER/SCH 提供器，不依赖测试替身 |

## 审查断言

- MER 第六查询先验证同店全部员工行的状态与商家归属，再过滤 ACTIVE/enabled；不能靠 SQL 状态谓词或归属 JOIN 隐藏事实损坏。命中目标 store_id 但 merchant_id 错配是 503，其他门店员工不属于本次查询。响应按员工数字 ID 去重排序，空列表与异常分开。
- SCH 能力和排班先验证相关行状态，再过滤 ENABLED/AVAILABLE；能力按具体 service_id，排班按同一 staff_id 和 store_id 的半开区间并集覆盖完整 `[from,to)`。能力表当前只有 ENABLED 已知值；无能力用无行表达，不制造 DISABLED。按冻结的 07 号 §4.4，目标 service_id 的全部能力行需验状态（合法悬挂行不加人，未知行 503）；排班读取范围为目标店、MER∩能力候选员工、与查询窗口相交的行，且同店候选的 null/倒置/零长区间不能被 SQL 过滤隐藏。其他店和合法不相交行不影响本次结果。
- `AvailabilityQueryService.page` 已在 `ScheduleReadStore.read` 中打开 `REQUIRES_NEW` 的 SCH 可重复读快照。真实提供器的 SCH Mapper 应加入此事务；若再次调用该 store 的 `read`，就会开启第二个快照，多个窗口可观察到不一致事实。
- 同一员工出现在不同服务的读侧候选人数中，不构成并发防超卖证明。预约锁位时的跨服务共享人员协议属 SCH-003 CCR；本切片不得标称已解决。
- 07/27/11 契约同步后再核对接口形状、实现状态；不改外部 HTTP/Schema/Event、默认开关和模块依赖边界。

## 执行记录

独立 HTTP 用例在 `backend/pet-boot/src/test/java/com/petplatform/boot/auth/ScheduleCapacityHttpTest.java`。它复用真实审批/签约/服务发布 HTTP 自举，SCH-002 的员工/能力/排班及窗口/预约仅用 SQL 种子，未注册 QualifiedStaffFactsPort 测试替身。JDK 21、隔离 MySQL `127.0.0.1:33454` 与 Redis `127.0.0.1:16383`；每个夹具使用随机独立库和 Redis 前缀，不触碰默认服务。不得用开发者自测代替本文件的独立结论。

### 执行与审查证据

- 被测生产提交：`66208ead07d6b606429aa41c0d75fd274e139720`；QA 测试文件仅在本分支新增，尚未并回实现分支时独立执行。
- 命令（`backend/`）：`mvn -q -pl pet-boot -am '-Dtest=ScheduleCapacityHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，环境中显式设 `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`、`AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33454/`、`AUTH_MYSQL_USER=root`、`AUTH_MYSQL_PASSWORD` 为空、`AUTH_REDIS_HOST=127.0.0.1`、`AUTH_REDIS_PORT=16383`。最终 2026-09-24 16:13 +08 执行；Surefire `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，进程退出码 0。首次执行有完整 Maven reactor BUILD SUCCESS；新增门店消失投影及真实 SQL 读故障反例后均重跑通过。
- Q1～Q5 的 SQL/HTTP 反例与 MER 第六查询无 owner、排序、不可变、NOT_FOUND 均 PASS。Q4 的真实读取故障通过仅在随机 fixture 库临时改名 `staff_service_capability` 构造，503 后在 `finally` 恢复，原查询再次 200；因此不是用空表伪装失败。Q6 默认关闭由源码 `@ConditionalOnProperty(... havingValue="true")` 与既有 `ScheduleQueryDisabledTest` 设计核对；本 QA 命令未重复运行该既有用例。
- 代码审查：MER 自有 Mapper 以目标 `store_id` 读原始员工行，再校验状态和归属；SCH 自有 Mapper 以目标 `service_id` 读全部能力行，候选员工的同店排班无状态谓词，合并半开区间。SCH 内层 `ScheduleStaffFactsReadStore` 为 `PROPAGATION_REQUIRED`，boot 传入与外层 `ScheduleReadStore` 相同 DataSource，因此能力/排班加入窗口与占用的 RR 事务；MER 保持独立 RR。未发现跨 biz 直接依赖或跨模块 Mapper/DO 访问。
- 剩余边界：同人跨服务只证明读侧可重复计入，不能证明 hold 并发防超卖；SCH-003 需按独立 CCR 处理。员工/能力/排班写入方未交付，故不声明真实录入链 E2E。OpenAPI 实现状态标签由主协调在最终集成证据齐备后更新。
