-- Pet Platform V1.0 async infrastructure schema v0.1
-- MySQL 8.0+
-- Technical infrastructure only; business SSOT remains the source of product rules.

CREATE TABLE IF NOT EXISTS async_task (
    id                  BIGINT       NOT NULL COMMENT 'Snowflake PK',
    task_no             BIGINT       NOT NULL COMMENT 'Snowflake external/audit number',
    task_key            VARCHAR(191) NOT NULL COMMENT 'Deterministic idempotent task key',
    owner_module        VARCHAR(32)  NOT NULL,
    task_type           VARCHAR(64)  NOT NULL,
    biz_type            VARCHAR(32)  NOT NULL,
    biz_id              BIGINT       NOT NULL,

    status              VARCHAR(32)  NOT NULL COMMENT 'READY/RUNNING/RETRY_WAIT/SUCCEEDED/CANCELED/DEAD',
    priority            INT          NOT NULL DEFAULT 0,
    execute_at          DATETIME(3)  NOT NULL,

    lease_owner         VARCHAR(128) NULL,
    lease_until         DATETIME(3)  NULL,

    retry_count         INT          NOT NULL DEFAULT 0,
    max_retry_count     INT          NOT NULL DEFAULT 10,
    retry_policy        VARCHAR(32)  NOT NULL DEFAULT 'FAST_INTERNAL',

    expected_version    BIGINT       NULL,
    payload_json        JSON         NULL,

    last_result_code    VARCHAR(64)  NULL,
    last_error_code     VARCHAR(64)  NULL,
    last_error_message  VARCHAR(1000) NULL,

    finished_at         DATETIME(3)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_async_task_no (task_no),
    UNIQUE KEY uk_async_task_key (task_key),
    KEY idx_async_task_due (status, execute_at, priority, id),
    KEY idx_async_task_lease (status, lease_until),
    KEY idx_async_task_biz (biz_type, biz_id, task_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable delayed/retry task';

CREATE TABLE IF NOT EXISTS async_task_attempt (
    id                  BIGINT       NOT NULL COMMENT 'Snowflake PK',
    task_id             BIGINT       NOT NULL,
    attempt_no          INT          NOT NULL,
    instance_id         VARCHAR(128) NOT NULL,
    started_at          DATETIME(3)  NOT NULL,
    finished_at         DATETIME(3)  NULL,
    result              VARCHAR(32)  NULL COMMENT 'SUCCESS/NOOP/RETRY/DEAD',
    error_code          VARCHAR(64)  NULL,
    error_message       VARCHAR(1000) NULL,
    duration_ms         BIGINT       NULL,
    created_at          DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_task_attempt (task_id, attempt_no),
    KEY idx_task_attempt_created (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Async task execution attempts';

CREATE TABLE IF NOT EXISTS reconciliation_issue (
    id                  BIGINT       NOT NULL COMMENT 'Snowflake PK',
    issue_no            BIGINT       NOT NULL COMMENT 'Snowflake audit number',
    issue_key           VARCHAR(191) NOT NULL COMMENT 'Deterministic idempotent issue key',
    category            VARCHAR(64)  NOT NULL,
    biz_type            VARCHAR(32)  NOT NULL,
    biz_id              BIGINT       NOT NULL,
    related_event_id    BIGINT       NULL,
    status              VARCHAR(32)  NOT NULL COMMENT 'OPEN/RETRYING/NEED_MANUAL/RESOLVED/IGNORED',
    severity            VARCHAR(16)  NOT NULL DEFAULT 'WARN',
    detail_json         JSON         NULL,
    first_detected_at   DATETIME(3)  NOT NULL,
    last_detected_at    DATETIME(3)  NOT NULL,
    resolved_at         DATETIME(3)  NULL,
    resolved_by         BIGINT       NULL,
    resolution_note     VARCHAR(1000) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_issue_no (issue_no),
    UNIQUE KEY uk_reconciliation_issue_key (issue_key),
    KEY idx_reconciliation_status (status, severity, last_detected_at),
    KEY idx_reconciliation_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Unresolved consistency/reconciliation issues';

-- Claim example (must run inside a short transaction).
-- MySQL 8 supports SKIP LOCKED.
--
-- SELECT id
-- FROM async_task
-- WHERE (
--        status IN ('READY', 'RETRY_WAIT')
--        AND execute_at <= NOW(3)
--       )
--    OR (
--        status = 'RUNNING'
--        AND lease_until < NOW(3)
--       )
-- ORDER BY priority DESC, execute_at ASC, id ASC
-- LIMIT 100
-- FOR UPDATE SKIP LOCKED;

-- Recommended deterministic task_key examples:
-- PAYMENT_EXPIRE:{paymentId}
-- RESERVATION_HOLD_EXPIRE:{reservationId}
-- ORDER_AUTO_CONFIRM:{orderId}:{confirmRound}
-- REFUND_MERCHANT_TIMEOUT:{refundApplicationId}
-- PAYMENT_CHANNEL_QUERY:{paymentId}
-- REFUND_CHANNEL_QUERY:{refundOrderId}
-- NOTIFICATION_DELIVERY_RETRY:{deliveryId}
