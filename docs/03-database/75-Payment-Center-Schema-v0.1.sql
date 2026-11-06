-- CCR-PAY-CENTER-001 阶段 1：收款补齐分账字段 + 业务无关支付中心。
-- Review/development only. Apply after SQL06/SQL40/SQL41 in an isolated test database.
-- 不执行生产迁移、不回填：历史 payment_order 行的新增列一律保持 NULL，不推测 settle_type/log_no/log_date。
-- 契约：docs/04-api/75-Payment-Center-Contract-v0.1.md。DEMO 业务表见 75-Payment-Center-Demo-Schema-v0.1.sql（仅测试库）。

ALTER TABLE payment_order
    -- 支付中心行不属于 ORDER；ORDER 行仍必须有 order_id（见 ck_payment_owner）。
    MODIFY COLUMN order_id BIGINT NULL,
    ADD COLUMN biz_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '支付中心业务类型；ORDER 现有路径阶段1写 NULL',
    ADD COLUMN biz_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '业务方单号；与 biz_type 成对',
    ADD COLUMN settle_type CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '预下单 settle_type：0 常规结算(不上送)、1 接分账；NULL=历史行未知',
    ADD COLUMN log_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '拉卡拉对账单流水号(posp)，分账 1293/2396 必填；来自已验签通知或查单',
    ADD COLUMN log_date DATE NULL
        COMMENT '拉卡拉对账单交易日期(posp, yyyyMMdd)；与 log_no 成对',
    ADD UNIQUE KEY uk_payment_biz (biz_type, biz_no),
    ADD CONSTRAINT ck_payment_owner
        CHECK (order_id IS NOT NULL OR (biz_type IS NOT NULL AND biz_no IS NOT NULL)),
    ADD CONSTRAINT ck_payment_biz_pair
        CHECK ((biz_type IS NULL) = (biz_no IS NULL)),
    ADD CONSTRAINT ck_payment_settle_type
        CHECK (settle_type IS NULL OR settle_type IN ('0', '1')),
    ADD CONSTRAINT ck_payment_log_pair
        CHECK ((log_no IS NULL) = (log_date IS NULL));

-- 支付中心 create 的 requestId 幂等绑定；同键异参返回 IDEMPOTENCY_KEY_CONFLICT。
-- 不复用 payment_intent_request：其 order_id NOT NULL 且语义绑定 ORDER。
CREATE TABLE payment_center_request (
    id BIGINT NOT NULL PRIMARY KEY,
    request_key VARBINARY(1024) NOT NULL COMMENT 'payment.center.create/USER/{operatorId}/{requestId}',
    biz_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    expire_at DATETIME(3) NOT NULL,
    payment_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_center_request (request_key),
    KEY idx_payment_center_request_biz (biz_type, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
