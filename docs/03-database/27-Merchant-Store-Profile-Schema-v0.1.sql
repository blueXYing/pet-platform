-- Merchant store profile (27号 §6.4 supplement, 2026-10-19): merchant-maintained store
-- basic information (PRD 5.7 店铺基础信息). Companion tables only — SQL06 merchant /
-- merchant_store are intentionally untouched, same discipline as SQL29's
-- merchant_profile_compat. Isolated validation input, NOT default Flyway.
-- UTC timestamps. One row per store, lazily created by the first approved-owner save;
-- reads before the first save project the documented defaults (version 0, all fields NULL).
-- Audit records who/when/which fields changed (field NAMES, never values) — profile text is
-- not a credential, and the row itself carries the current values.

CREATE TABLE merchant_store_profile (
    store_id          BIGINT        NOT NULL,
    merchant_id       BIGINT        NOT NULL,
    intro             VARCHAR(500)  NULL COMMENT '店铺简介 0-500 码点，NULL=未设置',
    announcement      VARCHAR(200)  NULL COMMENT '店铺公告 0-200 码点，NULL=未设置',
    business_hours    JSON          NULL COMMENT '营业时间段数组（informational，见 27号 §6.4），NULL=未设置',
    promo_level       TINYINT       NULL COMMENT '促销等级 1-9 折，NULL=未设置',
    promo_title       VARCHAR(100)  NULL COMMENT '促销文案标题 0-100 码点，NULL=未设置',
    promo_text        VARCHAR(100)  NULL COMMENT '促销文案说明 0-100 码点，NULL=未设置',
    photo_asset_ids   JSON          NULL COMMENT '店铺照片私有素材 ID 数组（ASCII 数字字符串，0-6 个，null 元素非法），NULL=未设置',
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        DATETIME(3)   NOT NULL,
    updated_at        DATETIME(3)   NOT NULL,
    PRIMARY KEY (store_id),
    KEY idx_mer_store_profile_merchant (merchant_id),
    CONSTRAINT chk_mer_store_profile_ids CHECK (store_id > 0 AND merchant_id > 0 AND version >= 0),
    CONSTRAINT chk_mer_store_profile_promo CHECK (promo_level IS NULL OR promo_level BETWEEN 1 AND 9),
    CONSTRAINT fk_mer_store_profile_store FOREIGN KEY (store_id) REFERENCES merchant_store(id),
    CONSTRAINT fk_mer_store_profile_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_store_profile_audit (
    id               BIGINT        NOT NULL,
    actor_type       VARCHAR(24)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id         BIGINT        NOT NULL,
    action_code      VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_id      BIGINT        NOT NULL,
    store_id         BIGINT        NOT NULL,
    request_key      VARBINARY(1024) NOT NULL,
    request_id       VARBINARY(512)  NOT NULL,
    trace_id         VARCHAR(128)  NULL,
    from_version     BIGINT        NULL,
    to_version       BIGINT        NOT NULL,
    changed_fields   VARCHAR(255)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次变更字段名清单（ASCII 逗号分隔，仅字段名不含值）',
    occurred_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_store_profile_audit_request (request_key),
    KEY idx_mer_store_profile_audit_store (store_id, occurred_at),
    CONSTRAINT chk_mer_store_profile_audit_ids CHECK (id > 0 AND actor_id > 0 AND merchant_id > 0 AND store_id > 0),
    CONSTRAINT chk_mer_store_profile_audit_actor CHECK (actor_type = 'USER'),
    CONSTRAINT chk_mer_store_profile_audit_action CHECK (action_code = 'merchant.store.profile.update'),
    CONSTRAINT chk_mer_store_profile_audit_version CHECK ((from_version IS NULL OR from_version >= 0) AND to_version >= 0),
    CONSTRAINT chk_mer_store_profile_audit_request CHECK (OCTET_LENGTH(request_key) BETWEEN 1 AND 1024 AND OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_mer_store_profile_audit_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128),
    CONSTRAINT fk_mer_store_profile_audit_store FOREIGN KEY (store_id) REFERENCES merchant_store(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
