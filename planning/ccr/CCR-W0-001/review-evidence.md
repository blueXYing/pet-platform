# CCR-W0-001 附页三：来源与审阅证据

规范版本0.1（2026-09-14）。基线 develop `ba782653bfbdd139795438378b6335c7d0d99211`。本页登记逐项来源引用与核对过程，供 Contract Owner 复核，不替代人工审批。

## 1. 权威来源引用（均为本分支实际读取的行号）

| 来源 | 位置 | 本提案引用点 |
|---|---|---|
| docs/03-database/06-核心数据库Schema-v0.1.sql | 第752~768行 integration_event_outbox | 附页一§1现状原文；缺口=event_version/occurred_at/lease_*/PUBLISHING注释/租约索引 |
| 同上 | 第770~778行 integration_event_consume_log | 附页一§4；缺口=idx_consume_log_event |
| 同上 | 第781~795行 §13设计说明 | 不加跨模块外键、模块内事务/唯一键保证一致性 |
| docs/05-events/08-Integration-Event-Catalog-v0.6.md | §1（第5~28行）Event Envelope | 信封字段：eventId/eventType/eventVersion/occurredAt/aggregateType/aggregateId(String)/traceId/payload；UNIQUE(event_id,consumer_name)幂等；事实式命名 |
| 同上 | §2（第30~49行）首批事件表 | aggregate 复合表示 ORDER/PAYMENT 原样入列；不新增事件类型 |
| 同上 | §14（第342~367行）Outbox 状态 | 四态 NEW/PUBLISHING/PUBLISHED/FAILED；最低字段清单（本提案映射基准） |
| 同上 | §15（第369~388行）消费幂等 | INSERT先申领+同事务；禁止先业务后补log |
| 同上 | §16（第390~401行）事件兼容规则 | 消费者按 eventType+eventVersion 路由 → event_version 必须入库 |
| docs/06-scheduler/09-Scheduler-Retry-Compensation-v0.5.md | §21（第830~875行）Outbox 发布语义 | 多消费者逐个幂等、失败可重试、重分发跳过已成功者、"全部必要消费者成功才完全完成" |
| 同上 | §22（第876~885行）Outbox 卡死恢复 | PUBLISHING+lease_until<now 接管；失败 retry_count+1/FAILED/退避；不物理删除；PUBLISHED 留痕归档 |
| docs/03-database/13-Async-Infra-Schema-v0.1.sql | async_task lease_owner/lease_until/idx_async_task_lease | 租约命名与索引约定对齐已合入PLAT-004组件，两套基础表统一语义 |
| AGENTS.md 技术基线 | Transactional Outbox、durable AsyncTask、Snowflake BIGINT/String、requestId幂等 | 决定2事务/ID/Clock边界 |
| planning/ccr/CCR-W2-IDEMP-001（已批）及 23号公共接口补充 | 公共ID/Clock/金额接口与幂等记录 | outbox 写入路径复用公共组件，不重造 |

## 2. 登记处CCR-W0-001原问题逐项回应

原登记（CCR_W0_REGISTER.md）：“核心 Outbox 表未提供 Scheduler 所要求的 PUBLISHING 租约恢复字段（lease_until 等），事件存储字段也需逐项对应。”

- PUBLISHING+租约恢复字段 → 决定1新增 lease_owner/lease_until、注释四态、idx_outbox_lease；决定2接管语义。
- 事件存储字段逐项对应 → 附页一§2逐字段映射表（§14全部11个逻辑字段+信封traceId选项）。
- 原登记"预计需补齐字段"与本次结论一致，无缩窄。

## 3. 本阶段核对过程

- 逐行读取上表全部来源段落（非摘要转述）；本页行号即读取位置。
- 与已合入组件对照：pet-event-api 现有 IntegrationEvent/IntegrationEventPublisher 壳与本信封定义一致；pet-task-core（async_task）租约列/索引命名即附页一对齐对象；pet-id-core HANDOFF 公共提供器为决定2 ID来源。
- 未修改任何权威 docs、代码、Schema、CI 配置；本分支差异仅为 planning/ 下新增4个Markdown与登记处1处状态更新（见§5）。
- 文档阶段无需运行测试；PR 指向 develop 后 CI（backend verify + repository-policy 等6 job）自动执行，以 CI 实际结果为准，不以历史绿灯冒充。

## 4. 留给实现阶段的未决（不构成本次审批对象）

- 退避基数/倍数/上限具体数值及测试断言；
- FAILED 持续失败的对账/告警接入方式（对齐 PLAT-004 DEAD 对账告警同类机制）；
- PUBLISHED 归档策略与保留期（V1 仅留痕）；
- pet-boot 逐文件装配清单与分发循环参数（批量、周期）；
- 权威06号Schema的字段/注释同步文本（两项决定获批后由 Schema Owner 落笔）与隔离测试库迁移脚本。

## 5. 本分支文件清单

```text
planning/ccr/CCR-W0-001.md                              （新增，主文+一页指南）
planning/ccr/CCR-W0-001/field-mapping.md                （新增，附页一）
planning/ccr/CCR-W0-001/transaction-recovery-design.md  （新增，附页二）
planning/ccr/CCR-W0-001/review-evidence.md              （新增，本页）
planning/CCR_W0_REGISTER.md                             （修改：CCR-W0-001段登记草案已交付）
```
