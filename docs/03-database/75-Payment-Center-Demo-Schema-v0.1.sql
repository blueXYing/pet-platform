-- CCR-PAY-CENTER-001 阶段 1：DEMO 业务方（测试专用）。
-- 仅用于隔离测试库 / SIT 联调库；永不进入生产迁移。Apply after 75-Payment-Center-Schema-v0.1.sql。

CREATE TABLE payment_demo_order (
    id BIGINT NOT NULL PRIMARY KEY,
    demo_no BIGINT NOT NULL COMMENT '作为支付中心 bizNo（十进制字符串）',
    request_key VARBINARY(1024) NOT NULL COMMENT 'payment.demo.start/USER/{operatorId}/{requestId}',
    user_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'CREATED/PAID/AMOUNT_MISMATCH',
    payment_id BIGINT NULL,
    paid_amount DECIMAL(18,2) NULL,
    paid_at DATETIME(3) NULL,
    source_event_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_demo_no (demo_no),
    UNIQUE KEY uk_payment_demo_request (request_key),
    UNIQUE KEY uk_payment_demo_event (source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
