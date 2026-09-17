# PLAT-006 持久层统一 MyBatis 迁移

Owner Role: Backend Core
Epic: EPIC-01
Story: ST-PLAT-02
Priority: P1
Wave: 2
Status: IN_PROGRESS_PRS_SUBMITTED
Dependencies: AUTH-001-c-login 交付合入;C-002 宠物页不阻塞
Phase Readiness: PREREQUISITE_MERGED_SCOPE_PENDING

2026-09-15用户裁决"全部转 MyBatis",见 docs/02-architecture/22-持久层统一MyBatis裁决-v1.0.md。原约定迁移波次在两个会话交付后启动；当前两项已合入，进入模块范围/Owner确认;迁移期间不再新增 JDBC 持久层代码。

## 2026-09-16 当前阶段

PR29裁决已合入，前置C登录PR31/33及宠物阶段PR34均已合入；旧“等待在跑会话交付”不再是当前阻断。迁移尚未实施，按既有裁决确定逐模块范围、唯一Writer和原有回归，不重复审批技术选型；本同步不执行代码迁移。

证据见[合并台账](../../progress/2026-09-16/PROGRESS_SYNC.md)；本次只同步事实，不扩大原Scope或启动新的实现阶段。

## 2026-09-16 迁移实施台账（当晚实施）

六模块已按裁决顺序完成迁移并提交 PR（基线 3dfb253，逐模块独立分支，迁移前后同套测试全绿、CI 六绿、整合组合验证通过）。完整证据见[迁移报告](../../progress/2026-09-16/PLAT006_MIGRATION_REPORT.md)。状态为 **PR待审**，非已合入。

| 模块 | 生产文件迁移 | PR/分支 | 状态 |
|---|---|---|---|
| thirdparty | AssetRegistryJdbcStore→AssetRegistryStore+AssetRegistryMapper | #39 / codex/plat006-mybatis-thirdparty | PR待审 |
| user | 三 Store+三服务 SET SESSION→PetMapper/UserAuthMapper/CommandIdempotencyMapper+SessionControl | #40 / codex/plat006-mybatis-user | PR待审 |
| event | Outbox/ConsumeGuard/Publisher→OutboxMapper/ConsumeLogMapper | #41 / codex/plat006-mybatis-event | PR待审 |
| task | JdbcAsyncTaskRepository→AsyncTaskMapper | #42 / codex/plat006-mybatis-task | PR待审 |
| id | JdbcSnowflakeNodeStore→SnowflakeNodeMapper | #43 / codex/plat006-mybatis-id | PR待审 |
| admin | AdminAuthStore 原生 java.sql 框架+内联 SQL→AdminAuthMapper+AdminEntities | #44 / codex/plat006-mybatis-admin | PR待审 |

整合组合：codex/plat006-integration-validation（六分支依次合并，clean verify 全绿；仅为验证，不代表合入）。

2026-09-17 审阅反馈修订：六 PR 均已追加提交，把注解内联 SQL 全部改为各模块 `resources/mapper/*.xml`（接口仅留签名与 @Param，装配改 mapperLocations=`classpath*:mapper/*.xml` 跨根扫描），SQL 逐字不变，各模块与组合验证重新全绿。详见迁移报告 §8。

## Allowed / Forbidden

逐模块转换对应 `infrastructure/persistence|provider|oss` 内持久化类与其 Mapper(新增 `infrastructure.persistence.mapper` 包);不改业务语义、不改表结构、不改权威契约、不动 Redis 易失存储、不动前端。公共 mybatis 版本/依赖调整按 17号骨架逐文件登记。

## Acceptance Criteria

1. 22号裁决所列 7 处全部转为 MyBatis Mapper(XML或注解,锁/事务 SQL 原样保留);
2. 每模块 PR 携带既有全部测试原样通过(行为断言不改)、ArchUnit/依赖检查通过;
3. 迁移完成后全仓 grep 无新增 JdbcTemplate/java.sql 持久化主代码(admin 原生 JDBC 清零);
4. 完成回执登记于 22号裁决文件。

## Required Tests

各模块既有 MySQL 测试全量回归(usr001/plat003/auth/osstest env);不新增功能测试,迁移不改行为。

## 交付与DoD

一模块一PR、CI六绿、人工合并;全部合入后 PLAT-006 DONE。
