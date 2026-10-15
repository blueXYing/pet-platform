-- Approved 2026-10-15 (33号裁决补充：运营处置动作授权). Isolated test schema for the
-- contract-63 v0.2 disposal face; production rollout runs db/admin-trade-migration
-- V32__admin_trade_disposal.sql (same DDL) behind the opt-in admin-trade migration guard,
-- never on application startup. Three append-only audit/idempotency rows, one per owning
-- kernel. No status vocabulary changes: order_stage keeps its existing values
-- (ADMIN_ABNORMAL_CLOSE is a cancel_reason fact), merchant reuses ACTIVE/OFFLINE,
-- refund_order gains no new status.

CREATE TABLE refund_admin_action (
    id                     BIGINT NOT NULL,
    refund_order_id        BIGINT NOT NULL,
    order_id               BIGINT NOT NULL,
    action_type            VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_key               VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_state             VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    acted_by_operator_id   BIGINT NOT NULL,
    acted_at               DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_admin_request (request_id),
    KEY idx_refund_admin_refund (refund_order_id, acted_at, id),
    KEY idx_refund_admin_operator (acted_by_operator_id, acted_at),
    CONSTRAINT chk_refund_admin_ids CHECK (id > 0 AND refund_order_id > 0 AND order_id > 0),
    CONSTRAINT chk_refund_admin_type CHECK (action_type IN ('CHANNEL_QUERY', 'RETRY')),
    CONSTRAINT chk_refund_admin_state CHECK (
        task_state IN ('READY','RUNNING','RETRY_WAIT','SUCCEEDED','DEAD','CANCELED')
        AND refund_status IN ('CREATED','PROCESSING','UNKNOWN','SUCCESS','FAILED')),
    CONSTRAINT chk_refund_admin_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 191),
    CONSTRAINT chk_refund_admin_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='运营退款处置审计（63号v0.2 渠道查单/重试；append-only；requestId 幂等绑定）';

CREATE TABLE order_admin_action (
    id                     BIGINT NOT NULL,
    order_id               BIGINT NOT NULL,
    store_id               BIGINT NOT NULL,
    action_type            VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    order_version_before   BIGINT NOT NULL,
    refund_order_id        BIGINT NOT NULL,
    acted_by_operator_id   BIGINT NOT NULL,
    acted_at               DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_admin_request (request_id),
    KEY idx_order_admin_order (order_id, acted_at, id),
    KEY idx_order_admin_operator (acted_by_operator_id, acted_at),
    CONSTRAINT chk_order_admin_ids CHECK (id > 0 AND order_id > 0 AND store_id > 0 AND refund_order_id > 0),
    CONSTRAINT chk_order_admin_type CHECK (action_type = 'ABNORMAL_CLOSE'),
    CONSTRAINT chk_order_admin_reason CHECK (
        reason_code IN ('STORE_OFFLINE','QUALIFICATION_INVALID','MERCHANT_UNREACHABLE','OTHER')
        AND CHAR_LENGTH(reason) BETWEEN 10 AND 500),
    CONSTRAINT chk_order_admin_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 191),
    CONSTRAINT chk_order_admin_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='运营异常关单审计（63号v0.2；append-only；requestId 幂等绑定）';

CREATE TABLE merchant_governance_action (
    id                     BIGINT NOT NULL,
    merchant_id            BIGINT NOT NULL,
    action_type            VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    status_before          VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_version       BIGINT NOT NULL,
    acted_by_operator_id   BIGINT NOT NULL,
    acted_at               DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_governance_request (request_id),
    KEY idx_merchant_governance_merchant (merchant_id, acted_at, id),
    CONSTRAINT chk_merchant_governance_ids CHECK (id > 0 AND merchant_id > 0),
    CONSTRAINT chk_merchant_governance_type CHECK (action_type = 'DISABLE'),
    CONSTRAINT chk_merchant_governance_reason CHECK (CHAR_LENGTH(reason) BETWEEN 10 AND 500),
    CONSTRAINT chk_merchant_governance_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 191),
    CONSTRAINT chk_merchant_governance_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商家治理动作审计（63号v0.2 下线；append-only；requestId 幂等绑定）';
