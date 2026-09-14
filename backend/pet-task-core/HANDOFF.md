# PLAT-004 Worker / Lease 组件阶段交接

**组件阶段可 Review；完整 PLAT-004 非 DONE，生产 Worker 未放行。**

任务 `01a09e9d-6178-70b1-98b8-27bc536ddae3`；分支 `codex/plat-004-worker-lease`；独立工作区 `C:/Users/Administrator/.codex/worktrees/44dc/宠物平台V1.0`。输入 develop / PR11 合并提交 `0cc7d0151cdae3500185957d2a35088d0b31e37f`。本阶段承接根 WORK_STATE 最新持续推进授权，不采用旧规划“未启动”文字作为阻断。

| 分工 | 本次交付 |
|---|---|
| 我负责 | Worker/Lease 实现、独立真实数据库、并发/崩溃/旧租约验证、架构回归、草稿 PR 与最终 head CI 回执 |
| 你只需审批 | 审阅本阶段 PR 后决定是否合入 develop；当前未 merge，不发布 main |
| 必看 | 本页、[测试计数](evidence/junit-summary.json)、[完整 Java21 构建](evidence/maven-clean-verify.txt)；最终 commit、PR 和 head CI 链接以 PR 正文回执为准 |
| 既有 CCR | [CCR-W2-IDEMP-001](../../planning/ccr/CCR-W2-IDEMP-001.md)及[S1/S2交接](../../planning/ccr/CCR-W2-IDEMP-001/s1-handoff.md)：生产 S2 独占/高水位/fencing 仍由原 PLAT-002 承接，无第二套 ID 实现 |

## 已通过的范围

本地 Java 21.0.11 / Maven 3.9.12 / MySQL 8.4.9，完整 `mvn -B -f backend/pom.xml clean verify`：41 reactor 项目成功；74 common + 22 task-core + 22 ArchUnit = **118 JUnit，零失败、错误、跳过**。另 13 Python 架构负例、28 Contract 回归通过；离线 Smoke 16 操作 / 13 写 / 132 ref / 13 String ID，不代表真实 HTTP 或业务 E2E。

| 测试映射 | 实际证据与边界 |
|---|---|
| W2-TASK-001 | 24 Worker 争抢 12 到期任务，无重复 claim；外部锁下 SKIP LOCKED；真实 task_key、attempt 唯一约束及冲突回滚。只验证既有任务行消费和 Schema 去重，未实现 producer 调度 API |
| W2-TASK-002 / TASK-001、002 基础 | 独立 JVM claim 提交后 `Runtime.halt(17)`，另一 JVM 恢复 exit 0；同 owner 接管 version 递增；旧 token 及过期未接管 token 拒写；task 与 attempt 必须配对提交 |
| W2-TASK-003 | Handler 无领取事务，可用另一连接 NOWAIT 锁任务行；长 Handler heartbeat 跨原租期仍归本 Worker；成功/NOOP/取消/重试/上限 DEAD 与 attempt 对应；关闭、RuntimeException、非致命 Error 可恢复 |
| W2-TASK-004 | 将注入 Clock 固定到 2099 年仍不能提前领取；重试 execute_at 使用同库 NOW；重试 requestId 保持不变；实际锁等待跨越租期后 heartbeat/complete 拒绝。只使用通用 TEST Handler |
| ARCH-001～005 | 原 Enforcer、22 ArchUnit 与 Python 门禁持续通过；无 biz 依赖、跨模块持久化或第二套 DisplayStatus |

首次真实并发暴露默认隔离级别下 attempt gap-lock 死锁，已显式 READ_COMMITTED 修复，task 行锁仍串行化 attempt 分配；保留[原始失败](evidence/qa-deadlock-original.txt)与[首轮说明](evidence/qa-first-failures.txt)。另修正 MySQL NOW 固定语句开始时间问题：先取得 task 行锁，再在新 UPDATE 中判断 lease，两个实际 `performance_schema` 锁等待反例通过。Transaction 最终只读复审无遗留阻断。

## 生产缺口与后续 Owner

**此次没有新增必须人工裁决的公共 Contract 变化，也没有新 CCR 需要重复批准。** 以下是完整生产能力未实现/未验收，不能用组件绿灯替代：

- PLAT-002 S2：真实 Snowflake 提供器、worker 独占、重启高水位、fencing 持久化方案及生产 Clock/boot 装配。本模块只要求显式注入接口，null provider 拒绝；生产 jar 无测试 ID、无默认 Bean。
- 原 PLAT-004 后续：producer 创建/同 task_key 合法调度更新 API、DEAD 后 reconciliation + 告警闭环、监控及生产装配/容量验证。现有 reconciliation 表未被本实现写入，SQL13 未改。生产需要这些能力，当前没有降低 Scheduler §10、§38 的要求。
- 各业务 Handler Owner：按既有 `TASK:{taskType}:{bizId}:{generation}` 映射 requestId；这里的 resolver 是必须注入的 SPI，不用 claim version / attempt / worker 代替 generation。退款 UNKNOWN 查原单号等业务状态、支付/预约释放/30分钟确认/退款等尚未实现和验收。
- 业务副作用仍由自己的幂等/CAS/唯一键保护。close 的线性化点是 Handler 前 dispatch 许可；许可先成立的 Handler 可继续执行，但 close 后本 Worker 不提交结果，不提前释放租约。Future 取消不是副作用停止证明。

## Owner 与复现

Backend Core 唯一写 task-core module pom、六个新增 main 类、`AsyncTaskWorkerMySqlTest`、`WorkerProcessProbe`及本目录交接/证据。QA 唯一写 `MySqlTestDatabase`、`MySqlTaskRepositoryTest`，并经根明确追加授权只修改 `.github/workflows/ci.yml` 的 backend MySQL service/env；其他 job 和门禁不变。Transaction 只读审查。pet-common、boot、根 POM、Schema/API/Event/SSOT/PRD、前端均未改。

本地专用实例位于 `D:/Temp/plat004-mysql-44dc/data`，仅回环端口 33440；未操作原 MySQL84 服务/数据库。测试使用随机新建 `plat004_test_*` 库，CREATE 成功才清理，不接管既有库；缺 MySQL 直接失败，不 skip。CI backend 同样使用独立 MySQL 8.4 service。连接变量及复现方法见 [README](README.md)；[源码 SHA256](evidence/source-hashes.json)、[生产 jar 清单](evidence/production-jar-contents.txt)可核对。

本阶段最终 PR 保持草稿及未合并；后续 Issue/Wave 不自动启动。
