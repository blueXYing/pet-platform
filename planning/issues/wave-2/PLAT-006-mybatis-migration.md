# PLAT-006 持久层统一 MyBatis 迁移

Owner Role: Backend Core
Epic: EPIC-01
Story: ST-PLAT-02
Priority: P1
Wave: 2
Status: PLANNED
Dependencies: AUTH-001-c-login 交付合入;C-002 宠物页不阻塞
Phase Readiness: PREREQUISITE_MERGED_SCOPE_PENDING

2026-09-15用户裁决"全部转 MyBatis",见 docs/02-architecture/22-持久层统一MyBatis裁决-v1.0.md。原约定迁移波次在两个会话交付后启动；当前两项已合入，进入模块范围/Owner确认;迁移期间不再新增 JDBC 持久层代码。

## 2026-09-16 当前阶段

PR29裁决已合入，前置C登录PR31/33及宠物阶段PR34均已合入；旧“等待在跑会话交付”不再是当前阻断。迁移尚未实施，按既有裁决确定逐模块范围、唯一Writer和原有回归，不重复审批技术选型；本同步不执行代码迁移。

证据见[合并台账](../../progress/2026-09-16/PROGRESS_SYNC.md)；本次只同步事实，不扩大原Scope或启动新的实现阶段。

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
