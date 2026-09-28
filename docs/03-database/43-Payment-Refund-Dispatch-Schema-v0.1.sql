-- Review/development only. Apply after SQL06/40/41 in an isolated test database.
-- No production migration, provider enablement, or historical backfill is implied.
CREATE TABLE payment_refund_dispatch (
    refund_order_id BIGINT NOT NULL PRIMARY KEY,
    refund_no BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    merchant_no VARCHAR(32) NOT NULL,
    term_no VARCHAR(32) NOT NULL,
    original_channel_trade_no VARCHAR(128) NOT NULL,
    payment_success_event_id BIGINT NOT NULL,
    original_paid_amount DECIMAL(18,2) NOT NULL,
    refund_amount DECIMAL(18,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    refund_binding_version BIGINT NOT NULL,
    channel_request_no VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32) NOT NULL COMMENT 'MAY_HAVE_SENT/QUERY_PENDING/VERIFIED_SUCCESS/VERIFIED_TERMINAL_FAILURE/RECONCILIATION_REQUIRED',
    may_have_sent_at DATETIME(3) NOT NULL,
    query_not_before DATETIME(3) NOT NULL,
    channel_refund_no VARCHAR(128) NULL,
    terminal_receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    terminal_result_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_refund_no (refund_no),
    UNIQUE KEY uk_payment_refund_payment (payment_id),
    UNIQUE KEY uk_payment_refund_channel_request (channel_request_no),
    KEY idx_payment_refund_query (state,query_not_before)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_refund_receipt (
    id BIGINT NOT NULL PRIMARY KEY,
    refund_order_id BIGINT NOT NULL,
    receipt_source VARCHAR(16) NOT NULL COMMENT 'SUBMIT/QUERY/CALLBACK',
    receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_channel_trade_no VARCHAR(128) NOT NULL,
    channel_request_no VARCHAR(128) NOT NULL,
    channel_refund_no VARCHAR(128) NULL,
    channel_status VARCHAR(32) NOT NULL,
    refund_amount DECIMAL(18,2) NULL,
    result_at DATETIME(3) NULL,
    received_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_refund_receipt (refund_order_id,receipt_sha256),
    KEY idx_payment_refund_receipt_status (refund_order_id,channel_status,received_at),
    KEY idx_payment_refund_receipt_channel (channel_refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
