-- CCR-PAY-CENTER-001 阶段 2：结算配置、结算单、T+d 到期与订单分账指令。
-- 契约：docs/04-api/78-Payment-Settlement-Contract-v0.1.md。
-- Apply after SQL06/SQL13/SQL40/SQL41/SQL75 in an isolated test database.
-- 对应 Flyway 迁移：backend/pet-boot/src/main/resources/db/settlement-migration/V38__payment_settlement.sql。

CREATE TABLE settlement_config (
    id              BIGINT         NOT NULL PRIMARY KEY,
    merchant_id     BIGINT         NOT NULL COMMENT '商家 ID',
    settle_days     INT            NOT NULL DEFAULT 7 COMMENT 'T+d 结算周期天数，范围 7..30',
    fee_rate        DECIMAL(7,4)   NOT NULL DEFAULT 0.6000 COMMENT '商家对外手续费率（百分比，如 0.6000 表示 0.6%）',
    enabled         TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      DATETIME(3)    NOT NULL,
    updated_at      DATETIME(3)    NOT NULL,
    UNIQUE KEY uk_settlement_config_merchant (merchant_id),
    CONSTRAINT ck_settlement_config_days CHECK (settle_days BETWEEN 7 AND 30),
    CONSTRAINT ck_settlement_config_fee CHECK (fee_rate >= 0.0000 AND fee_rate < 100.0000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_config_audit (
    id                  BIGINT          NOT NULL PRIMARY KEY,
    merchant_id         BIGINT          NOT NULL,
    config_id           BIGINT          NOT NULL,
    request_key         VARBINARY(1024) NOT NULL COMMENT 'settlement.config.upsert/{operatorType}/{operatorId}/{requestId}',
    before_settle_days  INT             NULL,
    after_settle_days   INT             NOT NULL,
    before_fee_rate     DECIMAL(7,4)    NULL,
    after_fee_rate      DECIMAL(7,4)    NOT NULL,
    before_enabled      TINYINT(1)      NULL,
    after_enabled       TINYINT(1)      NOT NULL,
    operator_type       VARCHAR(32)     NOT NULL,
    operator_id         BIGINT          NULL,
    reason              VARCHAR(256)    NULL,
    created_at          DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_settlement_config_audit_req (request_key),
    KEY idx_settlement_config_audit_merchant (merchant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_order (
    id                        BIGINT         NOT NULL PRIMARY KEY,
    payment_id                BIGINT         NOT NULL COMMENT '关联 payment_order.id',
    order_id                  BIGINT         NULL COMMENT '关联 pet_order.id；非订单业务(如 DEMO)为 NULL',
    biz_type                  VARCHAR(32)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BOOKING_ORDER / DEMO',
    biz_no                    VARCHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_id               BIGINT         NOT NULL,
    store_id                  BIGINT         NULL,
    original_price            DECIMAL(18,2)  NOT NULL COMMENT '服务原价快照',
    flash_discount_amount     DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '闪价优惠快照（original_price - price）',
    merchant_discount_amount  DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '商家出资优惠快照（V1 为 0）',
    platform_subsidy_amount   DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '平台券优惠快照',
    paid_amount               DECIMAL(18,2)  NOT NULL COMMENT '渠道实付金额快照',
    refunded_amount           DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '累计已退款金额',
    split_base                DECIMAL(18,2)  NOT NULL COMMENT '当前有效分账基数（分账前部分退款后重算）',
    fee_rate_snapshot         DECIMAL(7,4)   NOT NULL COMMENT '支付成功时快照的对外手续费率（百分比）',
    merchant_share            DECIMAL(18,2)  NOT NULL COMMENT 'floor(split_base * (1 - fee_rate/100))',
    split_amount              DECIMAL(18,2)  NULL COMMENT '实分给商家的目标金额（调 2396 后计算）',
    platform_retained_amount  DECIMAL(18,2)  NULL COMMENT '分给平台商户自己的留存金额（can_separate_amt - split_amount）',
    platform_subsidy_due      DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '平台应补差额（merchant_share - split_amount）',
    actual_merchant_amount    DECIMAL(18,2)  NULL COMMENT '渠道终态确认的商家实收（1296 detail.actual_amt，缺失时等于 split_amount）',
    channel_split_fee         DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '渠道分账手续费（1296 total_fee_amt）',
    verified_at               DATETIME(3)    NULL COMMENT 'T = 核销完成时间（UTC）',
    settle_days_snapshot      INT            NULL COMMENT '核销时快照的 d（7..30）',
    eligible_at               DATETIME(3)    NULL COMMENT '到期分账时间（verified_at + d 天，UTC）',
    due_schedule_round        INT            NOT NULL DEFAULT 0 COMMENT 'SETTLEMENT_DUE 调度轮次（用于生成唯一任务键）',
    reversed_amount           DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '分账后累计已回退商家份额（阶段 3）',
    active_instruction_id     BIGINT         NULL COMMENT '当前在途或成功的分账指令 ID',
    status                    VARCHAR(32)    NOT NULL COMMENT 'PENDING_TRIGGER/WAITING/ON_HOLD/SPLITTING/SPLIT_SUCCEEDED/SPLIT_FAILED/REVERSING/PARTIALLY_REVERSED/REVERSED/CANCELED',
    hold_reason               VARCHAR(64)    NULL COMMENT 'ON_HOLD 原因码',
    last_error_code           VARCHAR(64)    NULL,
    last_error_message        VARCHAR(256)   NULL,
    source_paid_event_id      BIGINT         NULL,
    source_verified_event_id  BIGINT         NULL,
    version                   BIGINT         NOT NULL DEFAULT 0,
    created_at                DATETIME(3)    NOT NULL,
    updated_at                DATETIME(3)    NOT NULL,
    UNIQUE KEY uk_settlement_order_payment (payment_id),
    UNIQUE KEY uk_settlement_order_biz (biz_type, biz_no),
    KEY idx_settlement_order_order (order_id),
    KEY idx_settlement_order_merchant_status (merchant_id, status, created_at),
    KEY idx_settlement_order_status_eligible (status, eligible_at),
    CONSTRAINT ck_settlement_order_amounts CHECK (
        original_price >= 0.00 AND flash_discount_amount >= 0.00
        AND merchant_discount_amount >= 0.00 AND platform_subsidy_amount >= 0.00
        AND paid_amount > 0.00 AND refunded_amount >= 0.00
        AND split_base >= 0.00 AND merchant_share >= 0.00
        AND platform_subsidy_due >= 0.00 AND reversed_amount >= 0.00
    ),
    CONSTRAINT ck_settlement_order_days CHECK (settle_days_snapshot IS NULL OR settle_days_snapshot BETWEEN 7 AND 30)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_instruction (
    id                      BIGINT          NOT NULL PRIMARY KEY,
    settlement_order_id     BIGINT          NOT NULL,
    payment_id              BIGINT          NOT NULL,
    merchant_id             BIGINT          NOT NULL,
    instruction_type        VARCHAR(16)     NOT NULL COMMENT 'SPLIT / FALLBACK',
    attempt_no              INT             NOT NULL COMMENT '同结算单同类型第几次尝试（从 1 递增）',
    out_separate_no         VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户分账指令流水号（商户号下唯一）',
    separate_no             VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '拉卡拉分账指令流水号',
    merchant_no             VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '平台收单商户号',
    receiver_no             VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商家拉卡拉分账接收方编号',
    log_no                  VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原交易对账单流水号',
    log_date                DATE            NOT NULL COMMENT '原交易对账单日期',
    total_amount            DECIMAL(18,2)   NOT NULL COMMENT '本次指令分账总金额（can_separate_amt）',
    merchant_amount         DECIMAL(18,2)   NOT NULL COMMENT '本次分给商家的金额',
    platform_amount         DECIMAL(18,2)   NOT NULL DEFAULT 0.00 COMMENT '本次分给平台自己的金额',
    actual_separate_amount  DECIMAL(18,2)   NULL COMMENT '1296 返回 actual_separate_amt',
    actual_merchant_amount  DECIMAL(18,2)   NULL COMMENT '1296 返回商家明细 actual_amt（缺失时等于 merchant_amount）',
    total_fee_amount        DECIMAL(18,2)   NULL COMMENT '1296 返回 total_fee_amt',
    merchant_fee_amount     DECIMAL(18,2)   NULL COMMENT '1296 返回商家明细 fee_amt',
    status                  VARCHAR(16)     NOT NULL COMMENT 'CREATED / SENT / PROCESSING / SUCCEEDED / FAILED',
    channel_status          VARCHAR(32)     NULL COMMENT '渠道原始 status',
    channel_final_status    VARCHAR(32)     NULL COMMENT '渠道原始 final_status',
    acc_result_desc         VARCHAR(256)    NULL COMMENT '账户端处理失败原因（1296）',
    submit_response_sha256  CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL,
    terminal_receipt_sha256 CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL,
    finish_date             DATE            NULL,
    sent_at                 DATETIME(3)     NULL,
    terminal_at             DATETIME(3)     NULL,
    result_event_id         BIGINT          NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              DATETIME(3)     NOT NULL,
    updated_at              DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_settlement_instr_out_no (out_separate_no),
    UNIQUE KEY uk_settlement_instr_attempt (settlement_order_id, instruction_type, attempt_no),
    UNIQUE KEY uk_settlement_instr_separate_no (separate_no),
    UNIQUE KEY uk_settlement_instr_event (result_event_id),
    KEY idx_settlement_instr_order (settlement_order_id, created_at),
    KEY idx_settlement_instr_status (status, updated_at),
    CONSTRAINT ck_settlement_instr_type CHECK (instruction_type IN ('SPLIT', 'FALLBACK')),
    CONSTRAINT ck_settlement_instr_status CHECK (status IN ('CREATED', 'SENT', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_settlement_instr_amounts CHECK (total_amount > 0.00 AND merchant_amount >= 0.00 AND platform_amount >= 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_instruction_receipt (
    id                      BIGINT          NOT NULL PRIMARY KEY,
    instruction_id          BIGINT          NOT NULL,
    receipt_sha256          CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_source          VARCHAR(16)     NOT NULL COMMENT 'QUERY / NOTIFICATION',
    channel_response_sha256 CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL,
    separate_no             VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NULL,
    cmd_type                VARCHAR(32)     NOT NULL,
    channel_status          VARCHAR(32)     NOT NULL,
    channel_final_status    VARCHAR(32)     NULL,
    total_amount            DECIMAL(18,2)   NULL,
    actual_separate_amount  DECIMAL(18,2)   NULL,
    total_fee_amount        DECIMAL(18,2)   NULL,
    acc_result_desc         VARCHAR(256)    NULL,
    finish_date             DATE            NULL,
    received_at             DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_settlement_receipt_dedupe (instruction_id, receipt_sha256),
    KEY idx_settlement_receipt_instr (instruction_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_action_request (
    id                  BIGINT          NOT NULL PRIMARY KEY,
    request_key         VARBINARY(1024) NOT NULL COMMENT '23号幂等键：resume/retry/trigger 等写操作',
    action_type         VARCHAR(32)     NOT NULL,
    settlement_order_id BIGINT          NOT NULL,
    request_fingerprint CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at          DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_settlement_action_req (request_key),
    KEY idx_settlement_action_order (settlement_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
