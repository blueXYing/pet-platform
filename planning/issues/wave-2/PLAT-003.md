# PLAT-003 Transactional Outbox 基础实现

Owner Role: Backend Core
Epic: EPIC-01
Story: ST-PLAT-02
Priority: P0
Wave: 2
Status: BLOCKED
Dependencies: PLAT-001
Phase Readiness: SPEC_READY

仅规划候选，未获Wave2启动批准。BLOCKED是完整Issue实现门禁；Phase Readiness仅表示批准后可进行明确阶段。阶段完成不等于整个Issue DONE。

## Allowed / Forbidden

原实现范围：`backend/pet-event-* backend/pet-boot`。不得改变产品规则、跨biz持久化、公共契约或他人文件。规范草案仅可在本Issue负责的planning/CCR_W0_REGISTER.md#CCR-W0-001及其planning/ccr附属草案中形成；不意味着可直接改权威Schema/HTTP/OpenAPI。公共文件、测试与boot装配按WAVE_2_PLAN.md登记唯一Writer。

## 来源与门禁

来源：06-核心数据库Schema-v0.1.sql outbox；Event08 §14；Scheduler09恢复与消费段落。

CCR-W0-001未批准；实现还需PLAT002公共接口与boot独占交接。

原Catalog依赖保留；以上实际Contract、API、视觉、共享文件及完整验收依赖同样是门禁，不以Catalog少写一项绕过。

## Acceptance Criteria

1. 先逐字段核对现有Outbox表与事件版本/发生时间/载荷/发布状态及租约恢复语义，提交CCR-W0-001审批，不预造DDL。
2. 获批后同一数据库事务保存业务事实与Outbox，事务回滚不得留下可发布事件。
3. 实现可恢复发布、重试及并发领取；重复发布允许按契约处理，但不能声称消息恰好一次。
4. 用通用事件/测试消费者验证幂等与失败恢复，不引入订单退款业务Handler或另一个消息架构。
5. 公共ID/Clock由PLAT002提供，boot装配逐文件独占，报告Schema/Event变更及迁移。

## Required Tests

原要求：TASK-005。新增可执行验收定义：W2-OUTBOX-001～003，见planning/WAVE_2_TEST_ACCEPTANCE.md。所有相关ARCH001～005持续通过。模块基础夹具只证明该能力，不把尚未实现的订单/支付场景编号直接报PASS。

## 交付与DoD

交付固定commit/工作区/PR、规范版本/审批、正反测试结果、未验收范围与风险。所有原AC、Contract及所需测试满足后才DONE；真实E2E未具备服务就保留未完成。Schema/Event变化显式披露，重大Contract和合入develop仍需人工批准。预计阶段顺序与本波承诺见READY_QUEUE_WAVE_2.md，不自动转到后续Wave。
