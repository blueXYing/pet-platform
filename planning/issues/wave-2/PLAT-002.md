# PLAT-002 公共 ID/时间/金额/CommandContext/幂等基线

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

PR10/11/13/16 已合入，公共接口、Hutool适配及节点协调组件已有源码与测试。生产宿主退出证明、高水位恢复、迁移/装配和完整幂等范围仍保留门禁；不重新起草已接受方案。

证据见[合并台账](../../progress/2026-09-16/PROGRESS_SYNC.md)；本次只同步事实，不扩大原Scope或启动新的实现阶段。

## Allowed / Forbidden

原实现范围：`backend/pet-common backend/*-api`。不得改变产品规则、跨biz持久化、公共契约或他人文件。规范草案仅可在本Issue负责的planning/ccr/CCR-W2-IDEMP-001.md及其planning/ccr附属草案中形成；不意味着可直接改权威Schema/HTTP/OpenAPI。公共文件、测试与boot装配按WAVE_2_PLAN.md登记唯一Writer。

## 来源与门禁

来源：技术基线05 §3；内部API07 §2/21；HTTP10 §2.3。

CCR-W2-IDEMP-001；公共持久化方案审批前不得完成幂等实现。

原Catalog依赖保留；以上实际Contract、API、视觉、共享文件及完整验收依赖同样是门禁，不以Catalog少写一项绕过。

## Acceptance Criteria

1. 沿用Java21、Snowflake BIGINT及JSON String；ID/Clock接口先固定交接，时区/回拨/worker标识和边界按既有规范说明，不私自替换ID策略。
2. 金额使用BigDecimal/DECIMAL(18,2)，JSON金额String；统一转换/校验不引入新的业务舍入或优惠规则，发现未定义业务精度走CCR。
3. CommandContext字段和requestId行为保持已批准含义；覆盖缺字段、重复命令和不同主体等反例。
4. 先提交幂等key/摘要/结果重放/处理中/原子事务/失败与保留期映射草案；未获批不加表或用Redis冒充持久化。
5. 获批后实现数据库约束支持的幂等组件，原子业务测试用模块内夹具，不提前实现订单；跨api广泛改动先登记逐文件Owner。
6. 交付ID/Clock固定提交、规范审批证据、测试与迁移影响；仅ID部分完成不标整项DONE。

## Required Tests

原要求：ORD-011,ORD-013。新增可执行验收定义：W2-IDEM-001～005，见planning/WAVE_2_TEST_ACCEPTANCE.md。所有相关ARCH001～005持续通过。模块基础夹具只证明该能力，不把尚未实现的订单/支付场景编号直接报PASS。

## 交付与DoD

交付固定commit/工作区/PR、规范版本/审批、正反测试结果、未验收范围与风险。所有原AC、Contract及所需测试满足后才DONE；真实E2E未具备服务就保留未完成。Schema/Event变化显式披露，重大Contract和合入develop仍需人工批准。预计阶段顺序与本波承诺见READY_QUEUE_WAVE_2.md，不自动转到后续Wave。
