# 持久层统一 MyBatis 裁决 v1.0

> 2026-09-15 用户明确裁决:"全部转 MyBatis"。本裁决确认 17号《Maven工程骨架设计》的 MyBatis Spring Boot 约定为全项目唯一持久层标准;存量 JDBC 系代码列入 PLAT-006 统一迁移。技术基线 05号"api 模块禁 Mapper"等边界不变。

## 1. 裁决内容

- 全项目持久层统一 MyBatis(骨架17号既定依赖与 `infrastructure.persistence.mapper` 包结构为准);
- 存量 JDBC/原生 JDBC 代码不立即返工,按 PLAT-006 迁移波次统一转换,迁移期间新旧并存但**不再新增 JDBC 持久层代码**(在跑会话按原计划交付,不因本裁决中断);
- 基础设施组件中的锁/事务 SQL(FOR UPDATE SKIP LOCKED / SET SESSION / ON DUPLICATE KEY 等)在 Mapper XML 中**原样保留**,不改写语义;测试断言行为而非 SQL 形态,迁移以测试全绿为准入。

## 2. 迁移范围(PLAT-006,7处)

| 模块 | 现状 | 迁移要点 |
|---|---|---|
| pet-admin-biz AdminAuthStore | 原生 java.sql | 最大一处;事务/行包装改 Mapper |
| pet-user-biz PetStore/CommandIdempotencyStore/UserAuthStore(在途) | JdbcTemplate | UserAuthStore 随登录会话合入后并入 |
| pet-event-core Outbox/ConsumeGuard/Publisher | JdbcTemplate | 模块需引入 mybatis(无 boot starter,用 mybatis-spring SqlSessionTemplate) |
| pet-task-core JdbcAsyncTaskRepository | JdbcTemplate | 同上 |
| pet-id-core JdbcSnowflakeNodeStore | JdbcTemplate | 同上 |
| pet-thirdparty-biz AssetRegistryJdbcStore(PR28) | JdbcTemplate | 随波次转换 |

## 3. 顺序与门禁

1. 等两个在跑会话(C端登录/C-002宠物页)交付合入;
2. 一模块一 PR,先易后难(thirdparty→user→event→task→id→admin),每 PR:转换+原测试全绿+ArchUnit 过;
3. 全部完成后在本裁决登记完成回执,17号无需修订(MyBatis 本就是其标准,此前偏离的是实现而非文档)。

## 4. 不变项

Redis 易失存储(会话/attempt/缓存)不是持久层,不迁;`*-api` 模块禁 Mapper/实体(基线05);ARCH-002 跨模块持久化红线不变;CCR-OSS-001 的 S3/注册表语义不变。
