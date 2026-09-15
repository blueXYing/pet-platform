-- Pet Platform V1.0 asset registry schema v0.1 (CCR-OSS-001)
-- MySQL 8.0+. Pages reference asset_key; URLs are served from this table so
-- storage/CDN can change without client releases. Rows are retired, never deleted.

CREATE TABLE IF NOT EXISTS asset_registry (
    id              BIGINT        NOT NULL COMMENT 'Snowflake PK',
    asset_key       VARCHAR(512)  NOT NULL COMMENT 'stable business key (source relative path)',
    s3_object_key   VARCHAR(512)  NOT NULL COMMENT 'assets/<sha256>.<ext>, content-addressed',
    public_url      VARCHAR(1024) NOT NULL,
    sha256          CHAR(64)      NOT NULL,
    bytes           BIGINT        NOT NULL,
    category        VARCHAR(64)   NOT NULL COMMENT 'c002-assets / c002-originals / m002-assets / m002-originals / ...',
    status          VARCHAR(16)   NOT NULL COMMENT 'ACTIVE/RETIRED',
    width           INT           NULL,
    height          INT           NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      DATETIME(3)   NOT NULL,
    updated_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_key (asset_key),
    KEY idx_asset_category_status (category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CCR-OSS-001 registry; retired rows keep history';
