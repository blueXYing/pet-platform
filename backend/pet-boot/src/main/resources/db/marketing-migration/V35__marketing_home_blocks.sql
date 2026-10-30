-- CCR-MKT-003: home operations content blocks (manual curation; sponsor fields reserved).
-- 63-Marketing-Storage-v0.1.sql carries the full fresh-install schema; this delta upgrades
-- deployments that already applied V34.

CREATE TABLE marketing_home_block (
    id                      BIGINT NOT NULL,
    block_code              VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_code           VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_code           VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title                   VARCHAR(100) NOT NULL,
    subtitle                VARCHAR(200) NULL,
    tag_name                VARCHAR(32) NULL,
    image_url               VARCHAR(512) NULL,
    target_type             VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_value            VARCHAR(255) NULL,
    city_code               VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sponsor_merchant_id     BIGINT NULL,
    sponsor_store_id        BIGINT NULL,
    sort_weight             INT NOT NULL DEFAULT 0,
    start_at                DATETIME(3) NOT NULL,
    end_at                  DATETIME(3) NOT NULL,
    status                  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version                 INT NOT NULL DEFAULT 1,
    created_by_id           BIGINT NOT NULL,
    created_at              DATETIME(3) NOT NULL,
    updated_at              DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_home_block_code (block_code),
    KEY idx_marketing_home_block_read (position_code, city_code, status, start_at, end_at, sort_weight, id),
    CONSTRAINT chk_marketing_home_block_position CHECK (
        position_code IN ('HOME_SERVICE','HOME_COMMUNITY','HOME_EXOTIC_PLANT')
    ),
    CONSTRAINT chk_marketing_home_block_template CHECK (
        template_code IN ('HOT_SERVICE_FEATURE','COMMUNITY_HOT','EXOTIC_PLANT')
    ),
    CONSTRAINT chk_marketing_home_block_target CHECK (
        target_type IN ('SERVICE_DIRECTORY','SERVICE_CATEGORY','STORE','SERVICE','CAMPAIGN','COMMUNITY','MINIAPP_PATH','NONE')
        AND (target_type = 'NONE' OR target_value IS NOT NULL)
    ),
    CONSTRAINT chk_marketing_home_block_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
    CONSTRAINT chk_marketing_home_block_time CHECK (start_at < end_at),
    CONSTRAINT chk_marketing_home_block_sort CHECK (sort_weight BETWEEN 0 AND 9999),
    CONSTRAINT chk_marketing_home_block_version CHECK (version > 0),
    CONSTRAINT chk_marketing_home_block_sponsor CHECK (
        (sponsor_merchant_id IS NULL AND sponsor_store_id IS NULL)
        OR (sponsor_merchant_id IS NOT NULL AND sponsor_store_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='首页运营内容位（热门服务/宠友圈热门/植物&异宠；预留商家竞价）';

CREATE TABLE marketing_home_block_audit (
    id              BIGINT NOT NULL,
    home_block_id   BIGINT NOT NULL,
    action_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_id     BIGINT NOT NULL,
    request_id      VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detail_json     JSON NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_home_block_audit_request (request_id),
    KEY idx_marketing_home_block_audit_row (home_block_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='首页内容位写动作 append-only 审计';
