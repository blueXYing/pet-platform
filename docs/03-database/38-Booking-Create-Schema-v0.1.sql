-- Internal atomic booking creation. Apply after 06 and 37 only in an approved isolated database.
-- No production migration or enabled timeout/payment worker is implied.
CREATE TABLE order_creation_request (
    id BIGINT NOT NULL PRIMARY KEY,
    request_key VARBINARY(1024) NOT NULL,
    canonical_version VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_canonical MEDIUMBLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    receipt_json JSON NULL,
    trace_id VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL,
    succeeded_at DATETIME(3) NULL,
    UNIQUE KEY uk_order_creation_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_creation_audit (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    request_key VARBINARY(1024) NOT NULL,
    request_id VARBINARY(512) NOT NULL,
    trace_id VARCHAR(128) NULL,
    occurred_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_order_creation_audit_order (order_id),
    UNIQUE KEY uk_order_creation_audit_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_booking_input_snapshot (
    order_id BIGINT NOT NULL PRIMARY KEY,
    service_address_ciphertext MEDIUMBLOB NULL,
    customer_remark_ciphertext MEDIUMBLOB NULL,
    created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE schedule_reservation_audit (
    id BIGINT NOT NULL PRIMARY KEY,
    reservation_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    request_id VARBINARY(512) NOT NULL,
    trace_id VARCHAR(128) NULL,
    occurred_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_reservation_audit_action (reservation_id, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE pet_order
    ADD COLUMN payment_expire_at DATETIME(3) NULL,
    ADD KEY idx_order_payment_expire (order_stage, payment_expire_at, id);
