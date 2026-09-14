# CCR-W0-001 附页二：事务方案与恢复语义设计

规范版本0.1（2026-09-14）。本页把调度器09-v0.5 §21/§22 与事件目录08-v0.6 §14/§15/§16 的要求落成可实现的状态机与操作序列。**本文是规范，不是实现代码；所有 SQL 为语义示意，最终以实现阶段评审代码为准。**

## 1. 写入路径（决定2"同库同事务"）

```text
模块业务事务 BEGIN
  1. 业务事实写入（模块自有表）
  2. INSERT integration_event_outbox（同连接同事务）
     id/event_id ← pet-id-core 公共提供器
     event_version/occurred_at/aggregate_*/event_type/payload ← 信封字段
     status = NEW，retry_count = 0，next_retry_at/lease_* = NULL
COMMIT   → 业务事实与待发布事件同时可见
ROLLBACK → 两者同时消失，不留可发布事件（W2-OUTBOX-001）
```

- Outbox 行是业务事务的一部分，不是事后补偿；禁止"先提交业务再补插 Outbox"的两段写。
- 事件发布循环只读已提交行，与业务事务无锁耦合；业务请求不等发布结果。
- 事件只表达已发生事实、命令式命名禁止（§1）、V1 首批事件以§2清单为准，本CCR不新增类型。

## 2. 状态机与转移

```text
NEW ──claim(租约)──► PUBLISHING ──全部必要消费者成功──► PUBLISHED(终态, published_at=now)
                        │
                        ├─消费者失败──► FAILED(retry_count+1, next_retry_at=退避)
                        │                  └──到期claim──► PUBLISHING(新租约)
                        └─进程崩溃/租约到期(lease_until<now)──► 其他Worker原子接管,仍为PUBLISHING
```

- **claim（领取）**：单条原子 UPDATE 完成状态转移+租约写入，WHERE 带守卫防并发双领；领取者随后按自身 lease_owner 读取自己租约内的行。
- **接管**：仅当 `status='PUBLISHING' AND lease_until < now` 时允许（§22原文）。接管是"换租约继续"，不是重置 retry_count；接管前已成功的消费者成果（consume_log）保留，不重做（W2-OUTBOX-002/003）。
- **FAILED 重试**：`status='FAILED' AND next_retry_at <= now` 可再次 claim。退避基数/倍数/上限在实现阶段固定数值并配测试；本规范只约束：单调不缩短、有上限、retry_count 持续递增。
- **终态**：PUBLISHED 后不再变更；不物理删除任何行；归档策略属运维范畴，V1 只留痕（§22"PUBLISHED 数据按运维归档策略长期留痕/归档"）。
- **无 DEAD 态**：持续失败停留在 FAILED，retry_count 无上限增长由对账/告警通道暴露（对齐 PLAT-004 已交付组件中"DEAD对账告警"同类思路，具体告警接入属实现阶段）。

## 3. 分发与"完全完成"判定（§21）

本地 dispatcher 逐事件执行：

```text
for 每个本租约内事件:
    necessaryConsumers = 代码内注册表[eventType]   # 必要消费者清单
    succeeded = SELECT consumer_name FROM integration_event_consume_log
                WHERE event_id = ?                  # 走 idx_consume_log_event
    待执行 = necessaryConsumers - succeeded
    if 待执行为空:
        status → PUBLISHED, published_at = now      # "完全完成"
    else:
        逐个调用待执行消费者（见§4），任一失败 → FAILED 转移
```

- **只有全部当前注册的必要消费者都成功才置 PUBLISHED**（§21原文语义）；部分成功时事件保持可重试，重分发时已成功消费者幂等跳过、仅执行失败者（W2-OUTBOX-003）。
- 注册表为**代码内配置**（eventType → 必需consumer名清单），部署期固定；注册表变更只影响变更后未完成事件的判定，不回溯已 PUBLISHED 行。
- 重复分发允许：崩溃/接管/重试都可能造成同一消费者收到同一事件多次，正确性由消费端幂等保证，**不声称恰好一次**。

## 4. 消费者幂等事务（§15/§21 合并语义）

两处原文的统一解释：INSERT 既是幂等申领也是成功事实，其持久化与业务变更同一事务：

```text
消费者事务 BEGIN
  1. INSERT consume_log(consumer_name, event_id, event_type, consumed_at)
     唯一键冲突 → 该消费者已成功，事务内 NOOP 直接提交成功（幂等跳过）
  2. 执行本消费者业务变更（需自身可重入或以步骤1为先决守卫）
COMMIT   → 成功事实与业务变更原子落库
ROLLBACK → 申领撤销，事件对该消费者仍可重试
```

- 禁止"先处理业务、再单独写 consume_log"（§15明文），否则崩溃窗口产生重复副作用。
- 消费者业务变更自身的 requestId 幂等（已批公共约定）与本机制叠加，不互相替代。

## 5. 崩溃场景对照（W2-OUTBOX-002 映射）

| 场景 | 时刻 | 结果 |
|---|---|---|
| E01 业务事务回滚 | Outbox INSERT 后 ROLLBACK | 无行残留，无可发布事件 |
| E02 领取后进程崩溃 | PUBLISHING、租约未到期 | 行停在 PUBLISHING；lease_until 过期后其他 Worker 接管，event_version/occurred_at/载荷原值重发 |
| E03 发布中消费者崩溃 | 部分消费者已 COMMIT | 接管后仅执行 consume_log 缺失的消费者；已成功部分不重做 |
| E04 发布成功但置终态前崩溃 | 全部消费者成功、未置 PUBLISHED | 接管后判定"待执行为空"→ 置 PUBLISHED；期间多轮判定均幂等 |

## 6. 实现归属与测试映射

- 代码归属：`backend/pet-event-core`（状态机/分发/恢复）、`backend/pet-event-api`（信封/发布接口，现有壳）、`backend/pet-boot`（装配，逐文件独占）。ID/Clock/金额一律用 pet-common/pet-id-core 公共组件，不在 event-core 重造。
- 验收定义：W2-OUTBOX-001（同事务提交/回滚）、W2-OUTBOX-002（失败/超时/崩溃/接管恢复，版本/时间/载荷保持，映射TASK-005/FLT-012）、W2-OUTBOX-003（重复发布/消费重放无重复副作用、部分失败不重做，映射CON-020）。测试需真实隔离MySQL，不得以H2冒充租约验证（WAVE_2_TEST_ACCEPTANCE执行条件）。
- 本阶段不交付以上任何代码；实现阶段派发前需本CCR两项决定获批且权威Schema同步。
