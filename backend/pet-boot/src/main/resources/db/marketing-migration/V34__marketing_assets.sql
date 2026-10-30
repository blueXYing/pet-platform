-- CCR-MKT-002: backend-uploaded marketing artwork and placement card templates.
-- 63-Marketing-Storage-v0.1.sql carries the full fresh-install schema; this delta upgrades
-- deployments that already applied V33.

ALTER TABLE marketing_placement
    ADD COLUMN template_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER image_url_override;

CREATE TABLE marketing_asset (
    id              BIGINT NOT NULL,
    content_type    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bytes           BIGINT NOT NULL,
    width           INT NOT NULL,
    height          INT NOT NULL,
    sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key      VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content         LONGBLOB NULL,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_id   BIGINT NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_marketing_asset_sha (sha256),
    KEY idx_marketing_asset_status (status, created_at),
    CONSTRAINT chk_marketing_asset_bytes CHECK (bytes BETWEEN 1 AND 2097152),
    CONSTRAINT chk_marketing_asset_size CHECK (width BETWEEN 1 AND 2000 AND height BETWEEN 1 AND 2000),
    CONSTRAINT chk_marketing_asset_status CHECK (status IN ('ACTIVE','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='营销公开素材（V1 DB 存储；URL 稳定，后续可换 CDN）';

CREATE TABLE marketing_asset_audit (
    id              BIGINT NOT NULL,
    asset_id        BIGINT NOT NULL,
    action_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id     BIGINT NOT NULL,
    request_id      VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_asset_audit_request (request_id),
    KEY idx_marketing_asset_audit_asset (asset_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='营销素材上传 append-only 审计';
