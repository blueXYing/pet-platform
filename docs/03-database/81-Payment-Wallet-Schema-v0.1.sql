-- CCR-PAY-CENTER-001 阶段 5：商家钱包与拉卡拉 LAEP D1 主动提现（1269/1319/1271/1272）。
-- 契约：docs/04-api/80-Payment-Settlement-Full-API-Contract-v0.1.md（§1、§4.4、§5、§6）。
-- Apply after 78/79/80 schemas in an isolated test database.
-- 对应 Flyway 迁移：backend/pet-boot/src/main/resources/db/settlement-migration/V41__payment_settlement_wallet.sql。

CREATE TABLE withdrawal_order (
    id                       BIGINT          NOT NULL PRIMARY KEY,
    mer_order_no             VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户提现单号（拉卡拉 1319/1271 幂等键）',
    merchant_id              BIGINT          NOT NULL COMMENT '商家 ID',
    receiver_no              VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '拉卡拉分账接收方编号',
    amount                   DECIMAL(18,2)   NOT NULL COMMENT '提现申请金额（元，含手续费）',
    fee_amount               DECIMAL(18,2)   NULL COMMENT '渠道提现手续费（元，1271/1272 drawFee）',
    net_amount               DECIMAL(18,2)   NULL COMMENT '预计/实际到账金额（amount - fee_amount）',
    draw_mode                VARCHAR(8)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'D1' COMMENT '拉卡拉提款模式（V1 固定 D1）',
    status                   VARCHAR(16)     NOT NULL COMMENT 'CREATED / PROCESSING / SUCCEEDED / FAILED',
    draw_jnl                 VARCHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '拉卡拉提款流水号（1319/1271/1272 drawJnl）',
    channel_state            VARCHAR(32)     NULL COMMENT '渠道原始 drawState（DRAW.ACCEPTED/FREEZE/PROCESSING/SUCCESS/FAILED）',
    settle_no                VARCHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '渠道结算流水号（1271/1272 settleNo）',
    fail_reason              VARCHAR(256)    NULL COMMENT '渠道拒绝或失败原因',
    requested_by_user_id     BIGINT          NOT NULL COMMENT '发起提现的认证商家主账号（OWNER）用户 ID',
    request_key              VARBINARY(1024) NOT NULL COMMENT 'requestId 幂等键',
    request_fingerprint      CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求参数指纹（同键异参防冲突）',
    submit_response_sha256   CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL,
    terminal_receipt_sha256  CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_event_id          BIGINT          NULL COMMENT 'Outbox 终态事件 ID（至多发布一次）',
    requested_at             DATETIME(3)     NOT NULL,
    sent_at                  DATETIME(3)     NULL,
    finished_at              DATETIME(3)     NULL,
    version                  BIGINT          NOT NULL DEFAULT 0,
    created_at               DATETIME(3)     NOT NULL,
    updated_at               DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_withdrawal_mer_order_no (mer_order_no),
    UNIQUE KEY uk_withdrawal_request_key (request_key),
    UNIQUE KEY uk_withdrawal_draw_jnl (draw_jnl),
    UNIQUE KEY uk_withdrawal_result_event (result_event_id),
    KEY idx_withdrawal_merchant_status (merchant_id, status, created_at),
    KEY idx_withdrawal_status_updated (status, updated_at),
    CONSTRAINT ck_withdrawal_status CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_withdrawal_amounts CHECK (
        amount > 0.00
        AND (fee_amount IS NULL OR fee_amount >= 0.00)
        AND (net_amount IS NULL OR net_amount >= 0.00)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE withdrawal_receipt (
    id                       BIGINT          NOT NULL PRIMARY KEY,
    withdrawal_id            BIGINT          NOT NULL COMMENT '关联 withdrawal_order.id',
    receipt_sha256           CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '回执规范化摘要去重键',
    receipt_source           VARCHAR(16)     NOT NULL COMMENT 'QUERY / NOTIFICATION',
    channel_response_sha256  CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL,
    draw_jnl                 VARCHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NULL,
    draw_state               VARCHAR(32)     NOT NULL,
    draw_amount              DECIMAL(18,2)   NULL,
    fee_amount               DECIMAL(18,2)   NULL,
    memo                     VARCHAR(256)    NULL,
    complete_time            DATETIME(3)     NULL,
    received_at              DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_withdrawal_receipt_dedupe (withdrawal_id, receipt_sha256),
    KEY idx_withdrawal_receipt_wd (withdrawal_id, received_at),
    CONSTRAINT ck_withdrawal_receipt_source CHECK (receipt_source IN ('QUERY', 'NOTIFICATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
