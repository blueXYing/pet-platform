-- CCR-PAY-CENTER-001 阶段 6：拉卡拉 1879/1877 日对账任务记录与差异台账。
-- 契约：docs/04-api/80-Payment-Settlement-Full-API-Contract-v0.1.md（§1、§4.6）。
-- Apply after 78-Payment-Settlement-Schema-v0.1.sql & 79-Payment-Settlement-Fallback-Schema-v0.1.sql in an isolated test database.
-- 对应 Flyway 迁移：backend/pet-boot/src/main/resources/db/settlement-migration/V42__payment_settlement_reconcile.sql。

CREATE TABLE reconcile_job_run (
    id              BIGINT          NOT NULL PRIMARY KEY,
    reconcile_date  DATE            NOT NULL COMMENT '对账业务日期（渠道时区）',
    merchant_no     VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '平台收单商户号',
    status          VARCHAR(16)     NOT NULL COMMENT 'RUNNING / COMPLETED / FAILED',
    channel_count   INT             NOT NULL DEFAULT 0 COMMENT '渠道分账指令数（1879）',
    local_count     INT             NOT NULL DEFAULT 0 COMMENT '本地分账指令数',
    diff_count      INT             NOT NULL DEFAULT 0 COMMENT '本次对账差异条数',
    error_message   VARCHAR(256)    NULL,
    started_at      DATETIME(3)     NOT NULL,
    finished_at     DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_reconcile_job_date_mch (reconcile_date, merchant_no),
    KEY idx_reconcile_job_status_date (status, reconcile_date),
    CONSTRAINT ck_reconcile_job_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_reconcile_job_counts CHECK (channel_count >= 0 AND local_count >= 0 AND diff_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reconcile_diff (
    id                       BIGINT          NOT NULL PRIMARY KEY,
    reconcile_date           DATE            NOT NULL COMMENT '对账业务日期',
    diff_key                 VARCHAR(128)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等去重键（重跑不重复生成）',
    biz_type                 VARCHAR(16)     NOT NULL COMMENT 'SPLIT / FALLBACK',
    diff_type                VARCHAR(32)     NOT NULL COMMENT 'MISSING_LOCAL / MISSING_CHANNEL / AMOUNT_MISMATCH / STATUS_MISMATCH / FEE_MISMATCH',
    settlement_order_id      BIGINT          NULL COMMENT '关联 settlement_order.id',
    instruction_id           BIGINT          NULL COMMENT '关联 settlement_instruction.id',
    out_separate_no          VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商户分账指令流水号',
    separate_no              VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '拉卡拉分账指令流水号',
    merchant_id              BIGINT          NULL COMMENT '关联商家 ID',
    local_amount             DECIMAL(18,2)   NULL COMMENT '本地记录金额',
    channel_amount           DECIMAL(18,2)   NULL COMMENT '渠道记录金额',
    local_fee_amount         DECIMAL(18,2)   NULL COMMENT '本地记录手续费',
    channel_fee_amount       DECIMAL(18,2)   NULL COMMENT '渠道记录手续费',
    local_status             VARCHAR(32)     NULL COMMENT '本地指令状态',
    channel_status           VARCHAR(32)     NULL COMMENT '渠道指令状态',
    detail                   VARCHAR(512)    NULL COMMENT '差异说明（只读告警，不自动改账）',
    status                   VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / RESOLVED',
    resolved_by_operator_id  BIGINT          NULL COMMENT '人工核销运营 ID',
    resolve_request_key      VARBINARY(1024) NULL COMMENT 'requestId 幂等键',
    resolve_note             VARCHAR(256)    NULL COMMENT '人工处理备注',
    resolved_at              DATETIME(3)     NULL COMMENT '人工处理时间（UTC）',
    version                  BIGINT          NOT NULL DEFAULT 0,
    created_at               DATETIME(3)     NOT NULL,
    updated_at               DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_reconcile_diff_key (diff_key),
    UNIQUE KEY uk_reconcile_diff_resolve_req (resolve_request_key),
    KEY idx_reconcile_diff_date_status (reconcile_date, status, diff_type),
    KEY idx_reconcile_diff_out_sep (out_separate_no),
    KEY idx_reconcile_diff_order (settlement_order_id),
    CONSTRAINT ck_reconcile_diff_biz CHECK (biz_type IN ('SPLIT', 'FALLBACK')),
    CONSTRAINT ck_reconcile_diff_type CHECK (diff_type IN ('MISSING_LOCAL', 'MISSING_CHANNEL', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'FEE_MISMATCH')),
    CONSTRAINT ck_reconcile_diff_status CHECK (status IN ('OPEN', 'RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
