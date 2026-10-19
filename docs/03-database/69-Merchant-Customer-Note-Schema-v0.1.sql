-- Merchant customer note (69号 extension, 2026-10-19 CCR expansion): one merchant-internal
-- note per (store, customer) derived from the store's own orders. Companion tables only —
-- SQL06 is untouched, same discipline as the 27号 §6.4 store-profile companion schema.
-- Isolated validation input, NOT default Flyway. UTC timestamps.
-- The customer itself has no stored row (it is a derived read-side grouping); the note row is
-- lazily created by the first OWNER save and carries its own optimistic version (0 before the
-- first save). Audit records who/when/which field changed (field NAMES, never values) — the
-- note is a merchant-internal memo, not a credential, and the row carries the current value.

CREATE TABLE merchant_customer_note (
    store_id         BIGINT        NOT NULL,
    merchant_id      BIGINT        NOT NULL,
    user_id          BIGINT        NOT NULL COMMENT '派生客户（本店订单 user_id 分组）',
    note             VARCHAR(200)  NULL COMMENT '商家内部备注 0-200 码点，空串语义=清空（存储 NULL）',
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       DATETIME(3)   NOT NULL,
    updated_at       DATETIME(3)   NOT NULL,
    PRIMARY KEY (store_id, user_id),
    KEY idx_mer_cust_note_merchant (merchant_id),
    CONSTRAINT chk_mer_cust_note_ids CHECK (store_id > 0 AND merchant_id > 0 AND user_id > 0 AND version >= 0),
    CONSTRAINT fk_mer_cust_note_store FOREIGN KEY (store_id) REFERENCES merchant_store(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_customer_note_audit (
    id               BIGINT        NOT NULL,
    actor_type       VARCHAR(24)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id         BIGINT        NOT NULL,
    action_code      VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_id      BIGINT        NOT NULL,
    store_id         BIGINT        NOT NULL,
    user_id          BIGINT        NOT NULL,
    command_id       BIGINT        NOT NULL,
    request_id       VARBINARY(36) NOT NULL,
    trace_id         VARCHAR(128)  NULL,
    from_version     BIGINT        NULL,
    to_version       BIGINT        NOT NULL,
    changed_fields   VARCHAR(255)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次变更字段名清单（ASCII 逗号分隔，仅字段名不含值）',
    occurred_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mer_cust_note_audit_target (store_id, user_id, occurred_at),
    CONSTRAINT chk_mer_cust_note_audit_ids CHECK (id > 0 AND actor_id > 0 AND merchant_id > 0 AND store_id > 0 AND user_id > 0 AND command_id > 0),
    CONSTRAINT chk_mer_cust_note_audit_actor CHECK (actor_type = 'USER'),
    CONSTRAINT chk_mer_cust_note_audit_action CHECK (action_code = 'merchant.customer.note.update'),
    CONSTRAINT chk_mer_cust_note_audit_version CHECK ((from_version IS NULL OR from_version >= 0) AND to_version >= 0),
    CONSTRAINT chk_mer_cust_note_audit_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 36),
    CONSTRAINT chk_mer_cust_note_audit_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128),
    CONSTRAINT fk_mer_cust_note_audit_store FOREIGN KEY (store_id) REFERENCES merchant_store(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
