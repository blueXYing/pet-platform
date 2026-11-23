-- V37: merchant marketing tools P1 (CCR-MKT-TOOLS-001, contract 76 / 71 v0.4).
-- Numbering note: planned as V34 in the CCR, but V34-V36 were already taken by
-- marketing_assets / marketing_home_blocks / hot_service_placement, so this lands as V37.
-- Structure-only migration: no backfill, existing assets stay PLATFORM-owned.

ALTER TABLE marketing_campaign
    ADD COLUMN description      VARCHAR(300) NULL AFTER subtitle,
    ADD COLUMN theme_code       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER tag_name,
    ADD COLUMN highlight_text   VARCHAR(100) NULL AFTER theme_code,
    ADD COLUMN play_type        VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER highlight_text,
    ADD COLUMN cover_asset_id   BIGINT NULL AFTER cover_url,
    ADD CONSTRAINT chk_marketing_campaign_play_type CHECK (
        play_type IS NULL OR play_type IN ('THEME')
    );

ALTER TABLE marketing_asset
    ADD COLUMN owner_type       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM' AFTER id,
    ADD COLUMN owner_merchant_id BIGINT NULL AFTER owner_type,
    ADD COLUMN owner_store_id   BIGINT NULL AFTER owner_merchant_id,
    ADD COLUMN purpose          VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER owner_store_id,
    ADD CONSTRAINT chk_marketing_asset_owner CHECK (
        owner_type IN ('PLATFORM','MERCHANT')
        AND (owner_type = 'PLATFORM' OR (owner_merchant_id IS NOT NULL AND purpose = 'CAMPAIGN_COVER'))
    ),
    ADD KEY idx_marketing_asset_owner (owner_type, owner_merchant_id, owner_store_id, status);
