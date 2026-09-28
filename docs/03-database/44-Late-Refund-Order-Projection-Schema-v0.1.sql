-- ORDER-owned projection of a late-payment refund. Apply only in an approved migration.
-- The REFUND domain remains the authority for the business refund and channel status.
CREATE TABLE IF NOT EXISTS order_late_refund_result (
    order_id             BIGINT        NOT NULL,
    refund_order_id      BIGINT        NOT NULL,
    payment_id           BIGINT        NOT NULL,
    refund_type          VARCHAR(16)   NOT NULL COMMENT 'FULL in this slice',
    refund_source        VARCHAR(32)   NOT NULL COMMENT 'LATE_PAYMENT_TIMEOUT in this slice',
    refund_amount        DECIMAL(18,2) NOT NULL,
    refund_status        VARCHAR(32)   NOT NULL COMMENT 'CREATED/SUCCESS; ORDER event projection',
    created_event_id     BIGINT        NULL,
    success_event_id     BIGINT        NULL,
    succeeded_at         DATETIME(3)   NULL,
    created_at           DATETIME(3)   NOT NULL,
    updated_at           DATETIME(3)   NOT NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_order_late_refund_id (refund_order_id),
    UNIQUE KEY uk_order_late_refund_created_event (created_event_id),
    UNIQUE KEY uk_order_late_refund_success_event (success_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
