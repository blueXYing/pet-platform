-- Review/development only. Apply after SQL40 in an isolated test database.
-- Never infer channel dispatch facts for preexisting payment_order rows.
CREATE TABLE payment_dispatch (
    payment_id BIGINT NOT NULL PRIMARY KEY,
    state VARCHAR(32) NOT NULL COMMENT 'PREPARED/MAY_HAVE_SENT/PARAMETERS_READY/UNKNOWN/FENCED_UNSENT/CLOSE_MAY_HAVE_SENT/CLOSE_ACKED/TERMINAL_CLOSED',
    preorder_req_time DATETIME(0) NULL,
    trade_req_date DATE NULL,
    timeout_express_minutes INT NULL,
    identity_hmac_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    may_have_sent_at DATETIME(3) NULL,
    preorder_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parameters_ciphertext VARBINARY(2048) NULL,
    parameters_iv BINARY(12) NULL,
    parameter_valid_until DATETIME(3) NULL,
    fenced_at DATETIME(3) NULL,
    close_may_have_sent_at DATETIME(3) NULL,
    close_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    terminal_query_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    terminal_confirmed_at DATETIME(3) NULL,
    terminal_close_capability TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_payment_dispatch_state (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing SQL40 receipts came only from the signed notification entry.
ALTER TABLE payment_channel_receipt
    ADD COLUMN receipt_source VARCHAR(16) NOT NULL DEFAULT 'NOTIFICATION',
    ADD COLUMN channel_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
