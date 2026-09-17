# PLAT-006 持久层统一 MyBatis 迁移报告（候选阶段）

> 2026-09-16。本报告登记 PLAT-006 六模块迁移的实施事实：生产直接 SQL 盘点、逐模块迁移与
> 回归证据、事务/锁/时区/幂等语义保持方式、整合验证组合。六模块 PR 均已提交（#39～#44），
> **尚未人工合入 develop**；PLAT-006 未标记 DONE。不改动 22号裁决规则，本文件只是阶段进展
> 与证据登记。

## 1. 环境与口径

- 基线：origin/develop `3dfb253`（PR37 合并后）；每模块独立 worktree、独立 `codex/plat006-mybatis-*` 分支。
- 本地隔离验证：原生 MySQL 8.4.9 @127.0.0.1:3306（root；各 fixture 只创建/删除随机库名）、
  Redis @127.0.0.1:16379（无 RDB/AOF）；Java Temurin 21.0.11、Maven 3.9.12。
- CI：GitHub Actions 六项（backend/frontend-inventory/repository-policy/contract-smoke/web-build/miniapp-weapp-build）。
- 本机解法备注：boot HTTP 测试的 `server.port` 经 `SpringApplicationBuilder.properties()` 传入为
  最低优先级默认值，被 application.yml 的 `${SERVER_PORT:8080}` 覆盖；本机 8080 被其他进程占用，
  以 `SERVER_PORT=0` 环境变量解决（不改测试）；CI 无此问题。

## 2. 迁移前后生产直接 SQL 盘点（实际扫描，非旧文档口径）

迁移前生产源码（src/main）直接 JDBC/SQL 入口共 15 个文件：

| 模块 | 文件 | 原实现 | 迁移后 |
|---|---|---|---|
| thirdparty | AssetRegistryJdbcStore（OssAssetSyncService/PresignedAssetUrlService/OssAssetSyncCli 经其构造） | JdbcTemplate | 删除，替换为 AssetRegistryStore + AssetRegistryMapper |
| user | PetStore、CommandIdempotencyStore、UserAuthStore | JdbcTemplate | 同签名 Store + PetMapper/UserAuthMapper/CommandIdempotencyMapper |
| user | PetService、UserProfileService、UserAuthService（事务内 SET SESSION×2） | JdbcTemplate.execute | SessionControl（SessionControlMapper，同事务连接） |
| event-core | JdbcOutboxRepository、JdbcOutboxConsumeGuard、TransactionalOutboxPublisher | JdbcTemplate | 同名类 + OutboxMapper/ConsumeLogMapper |
| task-core | JdbcAsyncTaskRepository | JdbcTemplate | 同名类 + AsyncTaskMapper |
| id-core | JdbcSnowflakeNodeStore | JdbcTemplate | 同名类 + SnowflakeNodeMapper |
| admin | AdminAuthStore（原生 java.sql 框架）、AdminAuthService、AdminAuditDelivery（内联 ~55 条 SQL） | Connection/PreparedStatement/ResultSet | AdminAuthStore（双 TransactionTemplate 框架）+ AdminAuthMapper + AdminEntities |

迁移后 `backend/*/src/main` grep `JdbcTemplate|java.sql.*` 仅剩 1 处：
pet-boot `AdminAuthConfiguration` 的 Flyway 迁移目标校验（`getConnection()/getCatalog()` 守卫，
装配/迁移基础设施，非表持久化，非新增）。测试代码中的 JdbcTemplate（建库 fixture、断言查询）
与 DataSource/事务管理基础设施按 22号裁决与任务口径保留。

## 3. 逐模块结果与证据

| 模块 | PR | 分支 | 迁移前基线 | 迁移后同套回归 | 全后端 clean verify |
|---|---|---|---|---|---|
| thirdparty | #39 | codex/plat006-mybatis-thirdparty | 4/4 绿 | 4/4 绿 | 绿（admin-biz 一次并发抖动单独复跑 34/34；boot 8080 占用见 §1） |
| user | #40 | codex/plat006-mybatis-user | 24/24 绿 | 24/24 绿 + boot 24/24 | 绿 |
| event-core | #41 | codex/plat006-mybatis-event | 10/10 绿 | 10/10 绿 + boot 24/24 | 绿 |
| task-core | #42 | codex/plat006-mybatis-task | 22/22 绿 | 22/22 绿 | 绿 |
| id-core | #43 | codex/plat006-mybatis-id | 41/41 绿 | 41/41 绿 + boot 24/24 | 绿 |
| admin | #44 | codex/plat006-mybatis-admin | 34/34 绿 | 34/34 绿 + boot 24/24 | 绿 |

- 断言零修改（仅 thirdparty PresignedAssetUrlTest 的 seed 引用随类名更新，断言不变）；
- 各 PR 的 GitHub CI 六项检查全绿（以 PR 页为准）；
- ArchUnit 22/22、check-module-deps PASS（含组合树）；
- 外部 Provider（微信、OSS/S3、Redis）全部使用既有测试替身，未触碰真实桶/真实微信链路。

## 4. 事务、锁、时区、幂等与恢复语义保持

- 通用机制：各模块经 SqlSessionFactoryBean（SpringManagedTransactionFactory）构建工厂，Mapper 调用
  经 SqlSessionTemplate 绑定到当前 Spring 事务连接——与原 JdbcTemplate 的 DataSourceUtils 参与方式
  等价；DuplicateKeyException 仍由 Spring SQLExceptionTranslator 转换（幂等绑定、微信首登并发、
  手机号唯一键、admin_attempt_creation 唯一键的既有冲突断言全部通过）。
- SET SESSION（time_zone='+00:00'、innodb_lock_wait_timeout=2）：改经 Mapper 在事务内首语句执行，
  与业务语句同连接；thirdparty upsert 的 SET SESSION 与 INSERT 固定在同一 SqlSession（原先无事务时
  可能落在池中不同连接，UTC 意图不稳定；测试/CI MySQL 均 UTC，可观察行为不变且意图确定化）。
- 行锁/条件更新：FOR UPDATE（含 SKIP LOCKED 领取、锁后重读重检、锁行后条件 UPDATE 的
  "NOW 语句开始固定、等待后重估"两步顺序）、ON DUPLICATE KEY、CAS（fence/reserved_through、
  owner_incarnation）、受影响行数!=1 断言，SQL 逐字保留。
- 隔离与传播：REQUIRES_NEW+READ_COMMITTED（user 准入/执行、outbox、task、id）与
  admin 读 REPEATABLE_READ/写 READ_COMMITTED 双模板原样；超时（10s/1s/语句 5s）经模板与
  defaultStatementTimeout 保持。
- 时间：UTC 日历 Timestamp 读写由 MyBatis InstantTypeHandler 等价承接；SELECT NOW(3)/UTC_TIMESTAMP(3)
  数据库时钟采样语句原样保留；id 模块 acquire 锁后采样、renew 不用等待 SELECT 时间戳的顺序不变。
- 提交语义：outbox 发布/消费仍要求加入调用方事务（断言保留）；admin 提交 ACK 丢失由提交阶段
  TransactionSystemException 精确映射为 CommitUnknown，FaultSource（Connection 代理注入 commit 失败）
  恢复套件 11/11 通过；id 迟到 ACK 拒绝发布与 1 秒预算断言不变。
- core 模块（event/task/id）按 22号裁决直接使用 mybatis+mybatis-spring（3.5.19/4.1.0，与
  starter 管理版本一致，在各模块 pom 声明避免多 PR 改父 POM），不引入 boot starter；
  独立构造与 CLI 路径（OssAssetSyncCli sync/--presign）不依赖 Boot 扫描。

## 5. 整合验证组合

- 组合工作树分支 `codex/plat006-integration-validation`：origin/develop `3dfb253` 依次合并
  六个模块分支（merge 提交 8fdefdd/1409e06/1cd426d/58061e9/272448b/7e0425e），无冲突。
- 组合后 check-module-deps PASS；全后端 clean verify 结果见 §6（完成后回填）。
- 本组合仅为验证，**不代表已合入 develop**。

## 6. 组合验证结果

（整合工作树 `codex/plat006-integration-validation` `mvn clean verify`：BUILD SUCCESS，
41 模块全绿，含 ArchUnit/模块依赖/契约检查。本机环境与 §1 相同。）

## 7. 剩余事项与风险

- 六 PR 待人工审阅合入；合入顺序无硬依赖（互不改公共文件；event/task/id 各自声明相同版本依赖）。
- 22号裁决 AC4 完成回执：待六 PR 合入后由最终文档提交登记 DONE 收讫。
- PLAT-002 生产门禁不因本迁移解除；真实微信联调、生产启用、生产数据库迁移均不在本次范围。
- 残留 java.sql 1 处（boot Flyway 目标校验）已审计为装配基础设施；如后续裁决要求彻底清零可另行处理。

## 8. 2026-09-17 修订：SQL 全部改为 XML mapper（审阅反馈）

用户审阅反馈不采用注解内联 SQL。六模块 PR 均已追加提交完成转换：

- Mapper 接口只保留方法签名与 `@Param` 参数名；全部 SQL 移入各模块
  `src/main/resources/mapper/*.xml`（8 个 XML：thirdparty 1、user 4、event 2、task 1、id 1、admin 1），
  SQL 文本与迁移版逐字一致（仅 XML 转义 `<`，如 `&lt;=`、`id&lt;&gt;`）；
- 装配改为 `SqlSessionFactoryBean.setMapperLocations(classpath*:mapper/*.xml)`（XML 命名空间注册接口；
  `classpath*:` 跨所有 classpath 根扫描——单根 `classpath:` 在多模块各自携带 `resources/mapper` 的组合场景下
  只解析第一个根，整合树曾因此报 SnowflakeNodeMapper 未注册，已修复并回归），
  其余（SpringManagedTransactionFactory、mapUnderscoreToCamelCase、admin defaultStatementTimeout=5）不变；
- 已核实 XML 进入构建产物（target/classes/mapper）；每模块同套测试全绿 + 全后端 clean verify 绿
  （thirdparty 4/4、user 24/24+boot 24/24、event 10/10、task 22/22、id 41/41、admin 34/34+boot 24/24），
  整合组合重新合并六分支后 clean verify 全绿。
- 环境备注：2026-09-17 晨本机隔离 Redis（16379）随宿主机重启丢失，按 CI 同款易失配置
  （`--save '' --appendonly no` 容器）重建后全部恢复；此为测试环境事实，与迁移代码无关。
