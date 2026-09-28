-- Review/development only. No automatic production migration or backfill.
ALTER TABLE pet_order ADD COLUMN cancel_reason VARCHAR(32) NULL;
ALTER TABLE payment_order
    ADD COLUMN store_id BIGINT NULL,
    ADD COLUMN merchant_id BIGINT NULL,
    ADD COLUMN user_id BIGINT NULL,
    ADD COLUMN merchant_no VARCHAR(32) NULL,
    ADD COLUMN term_no VARCHAR(32) NULL,
    ADD COLUMN sub_appid VARCHAR(64) NULL,
    ADD COLUMN currency VARCHAR(3) NULL,
    ADD COLUMN dispatch_state VARCHAR(32) NULL,
    ADD COLUMN channel_trade_no VARCHAR(128) NULL,
    ADD COLUMN channel_paid_amount DECIMAL(18,2) NULL,
    ADD COLUMN success_event_id BIGINT NULL,
    ADD UNIQUE KEY uk_payment_channel_trade (channel,channel_trade_no),
    ADD UNIQUE KEY uk_payment_success_event (success_event_id),
    ADD KEY idx_payment_store (store_id,id);
CREATE TABLE payment_intent_request (
    id BIGINT NOT NULL PRIMARY KEY,
    request_key VARBINARY(1024) NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_intent_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE payment_channel_receipt (
    id BIGINT NOT NULL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_trade_no VARCHAR(128) NULL,
    channel_status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(18,2) NULL,
    paid_amount DECIMAL(18,2) NULL,
    paid_at DATETIME(3) NULL,
    received_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_receipt (payment_id,receipt_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE order_payment_result (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    source_event_id BIGINT NOT NULL,
    channel_trade_no VARCHAR(128) NOT NULL,
    channel_paid_amount DECIMAL(18,2) NOT NULL,
    channel_paid_at DATETIME(3) NOT NULL,
    result_type VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_order_payment_result_order (order_id),
    UNIQUE KEY uk_order_payment_result_payment (payment_id),
    UNIQUE KEY uk_order_payment_result_event (source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
