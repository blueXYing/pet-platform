# CCR-W0-001 附页一：Outbox 逐字段映射

规范版本0.1（2026-09-14）。本页逐项核对 `integration_event_outbox` 现有定义（docs/03-database/06-核心数据库Schema-v0.1.sql 第752~768行）与权威要求（Event Catalog 08-v0.6 §1/§14/§16；Scheduler 09-v0.5 §21/§22）的对应关系。"§14最低清单"指 Event Catalog §14"字段至少包含"列表。

## 1. 现有表定义（原文摘录）

```sql
CREATE TABLE integration_event_outbox (
    id                    BIGINT       NOT NULL,
    event_id              VARCHAR(64)  NOT NULL,
    aggregate_type        VARCHAR(64)  NOT NULL,
    aggregate_id          BIGINT       NOT NULL,
    event_type            VARCHAR(128) NOT NULL,
    payload               JSON         NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/PUBLISHED/FAILED',
    retry_count           INT          NOT NULL DEFAULT 0,
    next_retry_at         DATETIME(3)  NULL,
    created_at            DATETIME(3)  NOT NULL,
    published_at          DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_id),
    KEY idx_outbox_publish (status, next_retry_at, created_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id, created_at)
)
```

## 2. 逐字段映射表

| §14逻辑字段 | 现有列 | 状态 | 结论 |
|---|---|---|---|
| event_id | event_id VARCHAR(64) | 具备 | 保留。Snowflake生成后按公共约定以String存储，uk_outbox_event 唯一 |
| event_type | event_type VARCHAR(128) | 具备 | 保留。取值服从 Event Catalog §2 首批事件清单 |
| event_version | （无） | **缺失** | 新增 `event_version INT NOT NULL DEFAULT 1`。信封§1必带；§16消费者按 eventType+eventVersion 路由；表内不存即无法恢复发布原始版本事件 |
| aggregate_type | aggregate_type VARCHAR(64) | 具备 | 保留。按信封原样存储；注意 §2 中 LatePaymentSucceededAfterTimeoutEvent 的聚合为 `ORDER/PAYMENT` 复合表示，原样入列即可 |
| aggregate_id | aggregate_id BIGINT | 具备 | 保留。信封 aggregateId 为 String（公共 ID String 约定），存储 BIGINT、边界用 pet-common 公共转换；容量/合法性校验在转换层完成 |
| payload_json | payload JSON | 具备 | 保留。命名映射 payload_json→payload，语义一致 |
| occurred_at | （无） | **缺失** | 新增 `occurred_at DATETIME(3) NOT NULL`。业务事实发生时间（信封 occurredAt）；与 created_at（发件箱行写入时间）语义不同，崩溃恢复重发时必须携带原值，不得用重发时刻替代 |
| publish_status | status VARCHAR(16) | 具备（命名差异） | 保留列名 `status`，登记映射 publish_status→status（与 async_task 等基础表命名惯例一致）。注释枚举**必须**由 `NEW/PUBLISHED/FAILED` 修正为 `NEW/PUBLISHING/PUBLISHED/FAILED`（§14四态；VARCHAR(16) 容纳 PUBLISHING 无需改型） |
| retry_count | retry_count INT | 具备 | 保留 |
| next_retry_at | next_retry_at DATETIME(3) NULL | 具备 | 保留。FAILED 退避到期扫描由现有 idx_outbox_publish(status, next_retry_at, created_at) 前缀覆盖 |
| published_at | published_at DATETIME(3) NULL | 具备 | 保留。置 PUBLISHED 终态时落值 |
| （信封 traceId） | （无） | §14未要求 | 可选新增 `trace_id VARCHAR(64) NULL`，仅供排查，不参与任何判定逻辑；不批准则 traceId 仅存 payload 内由生产者决定 |

## 3. 调度器§22要求的租约字段（§14未列、调度契约必需）

| 调度器要求 | 现有列 | 结论 |
|---|---|---|
| `lease_until < now` 时允许其他 Worker 接管（§22） | （无） | 新增 `lease_until DATETIME(3) NULL` |
| 接管者身份可观测、可审计（§22"其他Worker接管"的可执行化） | （无） | 新增 `lease_owner VARCHAR(128) NULL`。命名/语义对齐已合入 13号Schema async_task 的 lease_owner/lease_until 约定（PLAT-004 组件已按该约定实现，两套基础表不发明两种租约语义） |
| 接管扫描效率 | 无对应索引 | 新增 `idx_outbox_lease(status, lease_until)`，对齐 idx_async_task_lease |

## 4. 消费表 integration_event_consume_log（第770~778行）

| 项 | 现状 | 结论 |
|---|---|---|
| UNIQUE(consumer_name, event_id) | 具备 | 保留，即§15"若唯一键冲突→已消费"的物质基础 |
| consumed_at/event_type | 具备 | 保留。§21"写 consume_log SUCCESS"即已提交的行本身，表无需 status 列 |
| 按 event_id 聚合扫描 | **缺索引** | 新增 `idx_consume_log_event(event_id)`：dispatcher 判定"全部必要消费者成功"需按 event_id 反查 consume_log 全集，现有唯一键以 consumer_name 为前导无法服务该扫描 |

## 5. 建议后的完整列清单（示意，非DDL）

```text
id, event_id, event_type, event_version(新), aggregate_type, aggregate_id,
payload, status(注释四态), occurred_at(新), retry_count, next_retry_at,
lease_owner(新), lease_until(新), trace_id(可选新), created_at, published_at
UNIQUE: uk_outbox_event(event_id)
KEY: idx_outbox_publish(status,next_retry_at,created_at)
     idx_outbox_aggregate(aggregate_type,aggregate_id,created_at)
     idx_outbox_lease(status,lease_until)(新)
consume_log 另增 KEY idx_consume_log_event(event_id)(新)
```

## 6. 明确不改的部分

- 不改 uk_outbox_event 唯一键（event_id 全局唯一幂等基础，§1"唯一幂等"）。
- 不加跨模块外键（06号§13设计说明第1条）。
- 不为"必要消费者注册"新增表：按主文决定2采用代码内注册配置，注册清单属部署事实而非业务数据。
- 不引入 DEAD 状态：§14 仅四态；持续失败停留在 FAILED + retry_count 增长并触发对账告警，处置走运维/对账通道，不新增契约状态。
- 时区约定：DATETIME(3) 按既定时区存储，OffsetDateTime 转换统一经 pet-common 公共 Clock/编解码，不在 event-core 私造转换。
