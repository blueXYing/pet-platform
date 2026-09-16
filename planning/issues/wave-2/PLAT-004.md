# PLAT-004 Durable AsyncTask Worker/Lease 基础实现

Owner Role: Backend Core
Epic: EPIC-01
Story: ST-PLAT-02
Priority: P0
Wave: 2
Status: BLOCKED
Dependencies: PLAT-001
Phase Readiness: COMPONENT_MERGED_PRODUCTION_PENDING

已按逐阶段授权推进，当前阶段以以下合并记录为准；原Status表示完整Issue尚未验收关闭。既有AC、Required Tests和Allowed/Forbidden保持，不将阶段合并等同于整项DONE。

## 2026-09-16 当前阶段

PR12 Worker/Lease 已合入；PR16 已补齐可注入的生产ID组件代码，但不等于生产启用已完成。producer、DEAD对账/告警、业务Handler和生产装配仍缺。

证据见[合并台账](../../progress/2026-09-16/PROGRESS_SYNC.md)；本次只同步事实，不扩大原Scope或启动新的实现阶段。

## Allowed / Forbidden

原实现范围：`backend/pet-task-core backend/pet-boot`。不得改变产品规则、跨biz持久化、公共契约或他人文件。规范草案仅可在本Issue负责的planning/issues/wave-2/PLAT-004.md及其planning/ccr附属草案中形成；不意味着可直接改权威Schema/HTTP/OpenAPI。公共文件、测试与boot装配按WAVE_2_PLAN.md登记唯一Writer。

## 来源与门禁

来源：13-Async-Infra-Schema-v0.1.sql；Scheduler09 §§4～8；现有TaskExecutionContext/Result。

13号Schema已有；实现启动须有PLAT002提供的固定ID/Clock接口，非等待通用幂等整项完成。

原Catalog依赖保留；以上实际Contract、API、视觉、共享文件及完整验收依赖同样是门禁，不以Catalog少写一项绕过。

## Acceptance Criteria

1. 沿用async_task/async_task_attempt、task_key唯一键、lease/version字段，禁止另造一套任务Schema。
2. claim在短事务中使用现有SKIP LOCKED语义，执行Handler不持有领取事务锁。
3. 多个Worker并发领取、进程重启和租约过期接管可恢复；旧租约Worker不能覆盖新执行结果。
4. 按现有状态/attempt/retry/dead规范写结果与重试，不把任务状态当业务幂等唯一保障。
5. 使用真实MySQL验证数据库语义，内存模拟不可替代；测试Handler仅基础夹具，不实现支付10分钟、确认30分钟等业务。
6. ID/Clock单一公共来源；pet-boot装配与其他Issue串行交接，给实际并发/恢复证据。

## Required Tests

原要求：TASK-001,TASK-002。新增可执行验收定义：W2-TASK-001～004，见planning/WAVE_2_TEST_ACCEPTANCE.md。所有相关ARCH001～005持续通过。模块基础夹具只证明该能力，不把尚未实现的订单/支付场景编号直接报PASS。

## 交付与DoD

交付固定commit/工作区/PR、规范版本/审批、正反测试结果、未验收范围与风险。所有原AC、Contract及所需测试满足后才DONE；真实E2E未具备服务就保留未完成。Schema/Event变化显式披露，重大Contract和合入develop仍需人工批准。预计阶段顺序与本波承诺见READY_QUEUE_WAVE_2.md，不自动转到后续Wave。

交接分两层：固定ID/Clock接口与明确测试替身可支持开发；生产Worker完整DoD必须采用PLAT-002实际Snowflake提供器和Clock装配及可重复验证，不得以空接口/测试ID完成生产交付。无需等待公共幂等整项DONE，不另造重复ID实现。
