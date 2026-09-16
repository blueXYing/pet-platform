# CCR-W0-001 实现阶段交接(候选,未合并)

> 2026-09-16 状态同步：PR20已合入；以下“候选/未合并”属于交付时快照，生产和业务集成限制保持。

分支 `codex/plat-003-impl`,基线 develop `a002b58f`(PR19合并后)。本页是组件交付说明,PR未合并前一切以PR/CI实际结果为准。

## 交付内容

| 文件 | 说明 |
|---|---|
| pet-event-api/DispatchedEvent, IntegrationEventConsumer | 消费端SPI:分发器交给消费者原始payload JSON,消费者自带事务(claim+业务同事务) |
| pet-event-core/TransactionalOutboxPublisher | 事务内追加Outbox行;无事务直接拒绝;信封字段校验 |
| pet-event-core/JdbcOutboxRepository | 领取(NEW/到期FAILED/过期PUBLISHING租约接管)/心跳/markPublished/markFailed/release;REQUIRES_NEW短事务+READ_COMMITTED+UTC会话,对齐task-core纪律 |
| pet-event-core/JdbcOutboxConsumeGuard | 消费幂等申领:consume_log唯一键,回滚即撤销申领 |
| pet-event-core/OutboxDispatcher | 后台分发循环:注册表判定必要消费者→跳过已成功→失败退避→全部成功才PUBLISHED;心跳维持租约;无注册类型释放回NEW不误标FAILED |
| pet-boot/config/EventOutboxConfiguration | 装配,默认关闭且需生产SnowflakeIdGenerator bean(双重门禁) |
| .github/workflows/ci.yml | backend job新增PLAT003_MYSQL_* env(复用CI MySQL服务) |

## 验证(本地,隔离MySQL 8.4.9,独立临时实例)

- W2-OUTBOX-001(4测试):同事务提交/回滚/无事务拒绝/信封校验反例,全过。
- W2-OUTBOX-002(3测试):租约过期接管+原版本/时间/载荷保持+旧写入者被围栏;FAILED退避到期才可重领且retry_count保持;无消费者注册释放回NEW,全过。
- W2-OUTBOX-003(3测试):部分失败只重做失败消费者、已成功不重做;重放全跳过;申领唯一+回滚撤销,全过。
- pet-common 74项回归通过;ArchUnit 22项(含ARCH-002跨模块持久化)通过;全反应堆-DskipTests install编译通过。

## 尚缺(不声称完整PLAT-003 DONE)

- 生产Snowflake提供器未启用→boot装配保持关闭;启用是PLAT-002 S2生产门禁。
- 权威Schema迁移脚本未写(按批准不执行迁移);boot默认migration目录未动。
- 未接入任何业务事件/消费者(V1首批事件待各域实现);FAILED持续失败的对账/告警接入、PUBLISHED归档策略待后续。
- 本地未跑需Redis的AUTH测试(pet-admin-biz/pet-boot),以CI为准;本分支未触碰AUTH代码。
