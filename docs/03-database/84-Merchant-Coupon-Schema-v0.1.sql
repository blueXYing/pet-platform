-- 84-商家自建优惠券 Schema v0.1（CCR-MKT-MERCHANT-COUPON-001，契约 79）
-- 本文件是增量脚本：既有表的 ALTER 与新表的 CREATE。
-- 增量迁移与本文严格一致：
--   db/marketing-migration/V38__merchant_coupon.sql  = 第 1～3 节
--   db/order-migration/V32__order_coupon_funding.sql = 第 4 节
-- 新装库以 06 号 / 63 号的 CREATE TABLE 为准（已含下列列与约束），不要重复执行本文件。

-- ============================================================
-- 1. 券模板：归属与出资方
-- ============================================================
ALTER TABLE coupon_template
    ADD COLUMN owner_type        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM' COMMENT 'PLATFORM/MERCHANT' AFTER status,
    ADD COLUMN owner_merchant_id BIGINT NULL COMMENT '商家券模板的归属商家；平台券为 NULL' AFTER owner_type,
    ADD COLUMN funding_type      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM' COMMENT '出资方 PLATFORM/MERCHANT' AFTER owner_merchant_id,
    ADD CONSTRAINT chk_coupon_template_owner CHECK (
        (owner_type = 'PLATFORM' AND owner_merchant_id IS NULL AND funding_type = 'PLATFORM')
        OR (owner_type = 'MERCHANT' AND owner_merchant_id IS NOT NULL AND funding_type = 'MERCHANT')
    );

-- ============================================================
-- 2. 券活动：商家归属与审核态；活动名按归属商家唯一
-- ============================================================
ALTER TABLE coupon_campaign
    ADD COLUMN owner_type        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM' COMMENT 'PLATFORM/MERCHANT' AFTER template_id,
    ADD COLUMN owner_merchant_id BIGINT NOT NULL DEFAULT 0 COMMENT '商家券活动的归属商家；平台活动为 0' AFTER owner_type,
    ADD COLUMN owner_store_id    BIGINT NULL COMMENT '创建时所在门店；NULL 表示全商家门店' AFTER owner_merchant_id,
    ADD COLUMN created_by_type   VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM_OPERATOR' COMMENT '创建者类型' AFTER created_by_id,
    ADD COLUMN description       VARCHAR(300) NULL COMMENT '活动说明' AFTER created_by_type,
    ADD COLUMN reject_reason     VARCHAR(500) NULL COMMENT '运营驳回原因' AFTER description,
    ADD COLUMN submitted_at      DATETIME(3) NULL COMMENT '最近一次提交审核时间' AFTER reject_reason,
    ADD COLUMN reviewed_at       DATETIME(3) NULL COMMENT '最近一次审核时间' AFTER submitted_at,
    ADD COLUMN reviewed_by_type  VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审核人类型' AFTER reviewed_at,
    ADD COLUMN reviewed_by_id    BIGINT NULL COMMENT '审核人' AFTER reviewed_by_type,
    DROP INDEX uk_coupon_campaign_name,
    -- 72 号：同期不重名。已取消/已结束的活动名称为 NULL，唯一键对其不生效，即释放名称。
    ADD COLUMN live_name VARCHAR(50) GENERATED ALWAYS AS (
        CASE WHEN status IN ('CANCELED','ENDED') THEN NULL ELSE name END
    ) STORED COMMENT '未终结活动的名称；已取消/已结束为 NULL',
    ADD UNIQUE KEY uk_coupon_campaign_owner_live_name (owner_merchant_id, live_name),
    DROP CHECK chk_coupon_campaign_status,
    ADD CONSTRAINT chk_coupon_campaign_status CHECK (status IN ('DRAFT','PENDING_REVIEW','REJECTED','CANCELED','SCHEDULED','ACTIVE','PAUSED','ENDED')),
    ADD CONSTRAINT chk_coupon_campaign_owner CHECK (
        (owner_type = 'PLATFORM' AND owner_merchant_id = 0)
        OR (owner_type = 'MERCHANT' AND owner_merchant_id > 0)
    );

-- ============================================================
-- 3. 领取记录：同一用户可按每人限领多次领取
-- ============================================================
ALTER TABLE coupon_claim
    ADD COLUMN claim_no INT NOT NULL DEFAULT 1 COMMENT '同一用户在同一活动的第几次领取，从 1 起' AFTER coupon_instance_id,
    DROP INDEX uk_coupon_claim_user,
    ADD UNIQUE KEY uk_coupon_claim_user_no (campaign_id, user_id, claim_no);

CREATE TABLE merchant_coupon_budget (
    id              BIGINT         NOT NULL,
    campaign_id     BIGINT         NOT NULL,
    merchant_id     BIGINT         NOT NULL,
    budget_limit    DECIMAL(18,2)  NOT NULL,
    issued_amount   DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '已领取券面额合计',
    used_amount     DECIMAL(18,2)  NOT NULL DEFAULT 0.00 COMMENT '处于 USED 的券实例面额合计',
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      DATETIME(3)    NOT NULL,
    updated_at      DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_coupon_budget_campaign (campaign_id),
    KEY idx_merchant_coupon_budget_merchant (merchant_id, campaign_id),
    CONSTRAINT chk_merchant_coupon_budget CHECK (budget_limit > 0 AND issued_amount >= 0 AND used_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商家券预算账户（与券台账分离）';

CREATE TABLE merchant_coupon_budget_ledger (
    id                  BIGINT         NOT NULL,
    campaign_id         BIGINT         NOT NULL,
    merchant_id         BIGINT         NOT NULL,
    coupon_instance_id  BIGINT         NOT NULL,
    order_id            BIGINT         NULL,
    entry_type          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RESERVE/USE/UNUSE',
    amount              DECIMAL(18,2)  NOT NULL,
    issued_after        DECIMAL(18,2)  NOT NULL,
    used_after          DECIMAL(18,2)  NOT NULL,
    request_id          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at          DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_coupon_budget_ledger_request (request_id),
    KEY idx_merchant_coupon_budget_ledger_campaign (campaign_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商家券预算账本 append-only';

-- ============================================================
-- 4. 订单快照：券出资方与商家券面额
-- ============================================================
ALTER TABLE pet_order
    ADD COLUMN coupon_funding_type     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'PLATFORM/MERCHANT；无券为 NULL' AFTER discount_amount,
    ADD COLUMN merchant_coupon_discount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '出资方为商家的券面额；无商家券为 0.00' AFTER coupon_funding_type;
