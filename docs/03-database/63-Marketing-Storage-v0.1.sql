-- 63-Marketing-Storage-v0.1.sql
-- 营销活动中心 + 券活动存储（MKT-001 / CPN-003）。MySQL 8.0+，utf8mb4。
-- 与核心 Schema 分库/同库均可；生产默认不执行，由营销模块 opt-in Flyway 装配。
-- 所有 ID 为 Snowflake BIGINT；HTTP/JSON 使用十进制 String；金额 DECIMAL(18,2)。
-- 业务表 append/更新语义见 docs/04-api/71、72 号契约。
-- 依赖：C 端门店 promotion 投影读取 SQL27 merchant_store_profile（商家促销字段）；
-- 启用营销前须按 docs/03-database/27-Merchant-Store-Profile-Schema-v0.1.sql 安装该表。
-- 2026-11-17 商家营销工具 P1 增量（41 号补充 / CCR-MKT-TOOLS-001 / 契约 76、71 v0.4）：
--   marketing_campaign 增 description/theme_code/highlight_text/play_type/cover_asset_id；
--   marketing_asset 增 owner_type/owner_merchant_id/owner_store_id/purpose（存量默认 PLATFORM）。
--   迁移落地为 marketing-migration/V37__merchant_marketing_tools.sql（CCR 原计划 V34，因 V34–V36 已被占用顺延）。

CREATE TABLE marketing_campaign (
    id                          BIGINT NOT NULL,
    campaign_code               VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name                        VARCHAR(100) NOT NULL,
    owner_type                  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_merchant_id           BIGINT NULL,
    owner_store_id              BIGINT NULL,
    campaign_type               VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    participation_mode          VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                      VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title                       VARCHAR(100) NOT NULL,
    subtitle                    VARCHAR(200) NULL,
    description                 VARCHAR(300) NULL,
    tag_code                    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    tag_name                    VARCHAR(32) NULL,
    theme_code                  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    highlight_text              VARCHAR(100) NULL,
    play_type                   VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cover_url                   VARCHAR(512) NULL,
    cover_asset_id              BIGINT NULL,
    city_codes                  JSON NULL,
    service_category_codes      JSON NULL,
    start_at                    DATETIME(3) NOT NULL,
    end_at                      DATETIME(3) NOT NULL,
    enroll_start_at             DATETIME(3) NULL,
    enroll_end_at               DATETIME(3) NULL,
    enrollment_quota            INT NULL,
    coupon_campaign_id          BIGINT NULL,
    page_schema_json            JSON NULL,
    page_draft_version          INT NOT NULL DEFAULT 0,
    published_page_schema_json  JSON NULL,
    page_version                INT NOT NULL DEFAULT 0,
    sort_weight                 INT NOT NULL DEFAULT 0,
    reject_reason               VARCHAR(500) NULL,
    version                     INT NOT NULL DEFAULT 1,
    created_by_type             VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_id               BIGINT NOT NULL,
    created_at                  DATETIME(3) NOT NULL,
    updated_at                  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_campaign_code (campaign_code),
    KEY idx_marketing_campaign_status_time (status, start_at, end_at),
    KEY idx_marketing_campaign_owner (owner_type, owner_merchant_id, status),
    KEY idx_marketing_campaign_coupon (coupon_campaign_id),
    CONSTRAINT chk_marketing_campaign_owner CHECK (
        owner_type IN ('PLATFORM','MERCHANT')
        AND (owner_type = 'PLATFORM' OR (owner_merchant_id IS NOT NULL AND owner_store_id IS NOT NULL))
    ),
    CONSTRAINT chk_marketing_campaign_type CHECK (
        campaign_type IN ('DISPLAY','COUPON','MERCHANT_ENROLL')
    ),
    CONSTRAINT chk_marketing_campaign_participation CHECK (
        participation_mode IN ('PLATFORM_SELECTED','MERCHANT_ENROLL')
    ),
    CONSTRAINT chk_marketing_campaign_status CHECK (
        status IN ('DRAFT','PENDING_REVIEW','SCHEDULED','ONLINE','PAUSED','ENDED','CANCELED','REJECTED')
    ),
    CONSTRAINT chk_marketing_campaign_time CHECK (start_at < end_at),
    CONSTRAINT chk_marketing_campaign_enroll CHECK (
        enroll_start_at IS NULL OR enroll_end_at IS NULL OR enroll_start_at < enroll_end_at
    ),
    CONSTRAINT chk_marketing_campaign_quota CHECK (
        enrollment_quota IS NULL OR (enrollment_quota BETWEEN 1 AND 10000)
    ),
    CONSTRAINT chk_marketing_campaign_play_type CHECK (
        play_type IS NULL OR play_type IN ('THEME')
    ),
    CONSTRAINT chk_marketing_campaign_sort CHECK (sort_weight BETWEEN 0 AND 9999),
    CONSTRAINT chk_marketing_campaign_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='营销活动主档（平台/商家发起、会场、页面 Schema 版本）';

CREATE TABLE marketing_campaign_store (
    id              BIGINT NOT NULL,
    campaign_id     BIGINT NOT NULL,
    merchant_id     BIGINT NOT NULL,
    store_id        BIGINT NOT NULL,
    source          VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    offer_tag       VARCHAR(32) NULL,
    offer_title     VARCHAR(100) NULL,
    offer_copy      VARCHAR(100) NULL,
    sort_weight     INT NOT NULL DEFAULT 0,
    version         INT NOT NULL DEFAULT 1,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_campaign_store (campaign_id, store_id),
    KEY idx_marketing_campaign_store_store (store_id, status),
    KEY idx_marketing_campaign_store_list (campaign_id, status, sort_weight, id),
    CONSTRAINT chk_marketing_campaign_store_source CHECK (
        source IN ('PLATFORM_SELECTED','MERCHANT_ENROLLED','MERCHANT_OWN')
    ),
    CONSTRAINT chk_marketing_campaign_store_status CHECK (status IN ('ACTIVE','REMOVED')),
    CONSTRAINT chk_marketing_campaign_store_sort CHECK (sort_weight BETWEEN 0 AND 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='活动参与门店（平台选择/商家报名/商家自有）';

CREATE TABLE marketing_campaign_service (
    id              BIGINT NOT NULL,
    campaign_id     BIGINT NOT NULL,
    store_id        BIGINT NOT NULL,
    service_id      BIGINT NOT NULL,
    sort_weight     INT NOT NULL DEFAULT 0,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_campaign_service (campaign_id, service_id),
    KEY idx_marketing_campaign_service_store (campaign_id, store_id, status, sort_weight),
    CONSTRAINT chk_marketing_campaign_service_status CHECK (status IN ('ACTIVE','REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='活动可选单次服务范围（不改变单次服务规则）';

CREATE TABLE marketing_page_version (
    id                  BIGINT NOT NULL,
    campaign_id         BIGINT NOT NULL,
    version             INT NOT NULL,
    schema_json         JSON NOT NULL,
    published_by_type   VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_by_id     BIGINT NOT NULL,
    published_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_page_version (campaign_id, version),
    KEY idx_marketing_page_version_time (campaign_id, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='活动页 Schema 发布快照（回滚只切换版本）';

CREATE TABLE marketing_placement (
    id                      BIGINT NOT NULL,
    position_code           VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    campaign_id             BIGINT NOT NULL,
    city_code               VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    title_override          VARCHAR(100) NULL,
    subtitle_override       VARCHAR(200) NULL,
    image_url_override      VARCHAR(512) NULL,
    template_code           VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sort_weight             INT NOT NULL DEFAULT 0,
    start_at                DATETIME(3) NOT NULL,
    end_at                  DATETIME(3) NOT NULL,
    status                  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hot_service_top_singleton TINYINT GENERATED ALWAYS AS (
        CASE WHEN position_code = 'HOT_SERVICE_TOP' AND status = 'ENABLED' THEN 1 ELSE NULL END
    ) STORED COMMENT 'HOT_SERVICE_TOP 全平台单例：仅启用行取1，其余NULL；唯一键兜底',
    version                 INT NOT NULL DEFAULT 1,
    created_by_id           BIGINT NOT NULL,
    created_at              DATETIME(3) NOT NULL,
    updated_at              DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_marketing_placement_read (position_code, city_code, status, start_at, end_at, sort_weight, id),
    KEY idx_marketing_placement_campaign (campaign_id, status),
    UNIQUE KEY uk_marketing_placement_hot_top_singleton (hot_service_top_singleton),
    CONSTRAINT chk_marketing_placement_position CHECK (
        position_code IN ('HOME_BANNER','HOME_ACTIVITY','STORE_LIST_TOP','HOT_SERVICE_TOP')
    ),
    CONSTRAINT chk_marketing_placement_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
    CONSTRAINT chk_marketing_placement_time CHECK (start_at < end_at),
    CONSTRAINT chk_marketing_placement_sort CHECK (sort_weight BETWEEN 0 AND 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='首页活动位/商家列表置顶/首页 Banner 投放';

CREATE TABLE marketing_enrollment (
    id              BIGINT NOT NULL,
    campaign_id     BIGINT NOT NULL,
    merchant_id     BIGINT NOT NULL,
    store_id        BIGINT NOT NULL,
    status          VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    offer_tag       VARCHAR(32) NULL,
    offer_title     VARCHAR(100) NULL,
    offer_copy      VARCHAR(100) NULL,
    service_ids     JSON NULL,
    materials       JSON NULL,
    reject_reason   VARCHAR(500) NULL,
    submitted_at    DATETIME(3) NOT NULL,
    decided_at      DATETIME(3) NULL,
    decided_by      BIGINT NULL,
    version         INT NOT NULL DEFAULT 1,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_enrollment_store (campaign_id, store_id),
    KEY idx_marketing_enrollment_status (campaign_id, status, submitted_at, id),
    KEY idx_marketing_enrollment_merchant (merchant_id, status, submitted_at),
    CONSTRAINT chk_marketing_enrollment_status CHECK (
        status IN ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED','WITHDRAWN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='平台招商活动商家报名/审核';

CREATE TABLE marketing_campaign_audit (
    id              BIGINT NOT NULL,
    campaign_id     BIGINT NOT NULL,
    action_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status       VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_type   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id     BIGINT NOT NULL,
    request_id      VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detail_json     JSON NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_campaign_audit_request (request_id),
    KEY idx_marketing_campaign_audit_campaign (campaign_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='活动/投放/页面写动作 append-only 审计';

CREATE TABLE marketing_enrollment_audit (
    id              BIGINT NOT NULL,
    enrollment_id   BIGINT NOT NULL,
    action_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status       VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_type   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id     BIGINT NOT NULL,
    reason          VARCHAR(500) NULL,
    request_id      VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_enrollment_audit_request (request_id),
    KEY idx_marketing_enrollment_audit_row (enrollment_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='商家报名/审核 append-only 审计';

CREATE TABLE marketing_command_idempotency (
    id                  BIGINT NOT NULL,
    request_key         VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_type        VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_sha256       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_canonical    LONGBLOB NOT NULL,
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json        JSON NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_marketing_command_request (request_key),
    CONSTRAINT chk_marketing_command_status CHECK (status IN ('IN_PROGRESS','SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='营销写动作 requestId 幂等绑定';

CREATE TABLE marketing_asset (
    id              BIGINT NOT NULL,
    owner_type      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM',
    owner_merchant_id BIGINT NULL,
    owner_store_id  BIGINT NULL,
    purpose         VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
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
    KEY idx_marketing_asset_owner (owner_type, owner_merchant_id, owner_store_id, status),
    CONSTRAINT chk_marketing_asset_bytes CHECK (bytes BETWEEN 1 AND 2097152),
    CONSTRAINT chk_marketing_asset_size CHECK (width BETWEEN 1 AND 2000 AND height BETWEEN 1 AND 2000),
    CONSTRAINT chk_marketing_asset_status CHECK (status IN ('ACTIVE','RETIRED')),
    CONSTRAINT chk_marketing_asset_owner CHECK (
        owner_type IN ('PLATFORM','MERCHANT')
        AND (owner_type = 'PLATFORM' OR (owner_merchant_id IS NOT NULL AND purpose = 'CAMPAIGN_COVER'))
    )
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

CREATE TABLE coupon_campaign (
    id                BIGINT NOT NULL,
    name              VARCHAR(50) NOT NULL,
    template_id       BIGINT NOT NULL,
    owner_type        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM' COMMENT 'PLATFORM/MERCHANT（契约 79）',
    owner_merchant_id BIGINT NOT NULL DEFAULT 0 COMMENT '商家券活动的归属商家；平台活动为 0',
    owner_store_id    BIGINT NULL COMMENT '创建时所在门店；NULL 表示全商家门店',
    audience_type     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    per_user_limit    INT NOT NULL DEFAULT 1,
    total_limit       INT NOT NULL,
    budget_limit      DECIMAL(18,2) NOT NULL,
    issued_count      INT NOT NULL DEFAULT 0,
    issued_amount     DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    start_at          DATETIME(3) NOT NULL,
    end_at            DATETIME(3) NOT NULL,
    claim_method      VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    live_name         VARCHAR(50) GENERATED ALWAYS AS (
        CASE WHEN status IN ('CANCELED','ENDED') THEN NULL ELSE name END
    ) STORED COMMENT '未终结活动的名称；已取消/已结束为 NULL（契约 79，72 号：同期不重名）',
    version           INT NOT NULL DEFAULT 1,
    created_by_id     BIGINT NOT NULL,
    created_by_type   VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLATFORM_OPERATOR' COMMENT '创建者类型',
    description       VARCHAR(300) NULL COMMENT '活动说明',
    reject_reason     VARCHAR(500) NULL COMMENT '运营驳回原因',
    submitted_at      DATETIME(3) NULL COMMENT '最近一次提交审核时间',
    reviewed_at       DATETIME(3) NULL COMMENT '最近一次审核时间',
    reviewed_by_type  VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审核人类型',
    reviewed_by_id    BIGINT NULL COMMENT '审核人',
    created_at        DATETIME(3) NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_campaign_owner_live_name (owner_merchant_id, live_name),
    KEY idx_coupon_campaign_status_time (status, start_at, end_at),
    KEY idx_coupon_campaign_template (template_id, status),
    CONSTRAINT chk_coupon_campaign_audience CHECK (audience_type IN ('ALL','NEW_USER')),
    CONSTRAINT chk_coupon_campaign_claim CHECK (claim_method = 'ACTIVE_CLAIM'),
    CONSTRAINT chk_coupon_campaign_status CHECK (status IN ('DRAFT','PENDING_REVIEW','REJECTED','CANCELED','SCHEDULED','ACTIVE','PAUSED','ENDED')),
    CONSTRAINT chk_coupon_campaign_owner CHECK (
        (owner_type = 'PLATFORM' AND owner_merchant_id = 0)
        OR (owner_type = 'MERCHANT' AND owner_merchant_id > 0)
    ),
    CONSTRAINT chk_coupon_campaign_limit CHECK (
        per_user_limit BETWEEN 1 AND 10 AND total_limit BETWEEN 1 AND 1000000
        AND budget_limit > 0 AND start_at < end_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='券活动/批次（关联既有券模板，仅主动领取；商家券活动见契约 79）';

CREATE TABLE coupon_claim (
    id                  BIGINT NOT NULL,
    campaign_id         BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    coupon_instance_id  BIGINT NOT NULL,
    claim_no            INT NOT NULL DEFAULT 1 COMMENT '同一用户在同一活动的第几次领取，从 1 起（契约 79）',
    claimed_at          DATETIME(3) NOT NULL,
    request_id          VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_claim_user_no (campaign_id, user_id, claim_no),
    UNIQUE KEY uk_coupon_claim_instance (coupon_instance_id),
    UNIQUE KEY uk_coupon_claim_request (request_id),
    KEY idx_coupon_claim_user_time (user_id, claimed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='C 端券活动领取记录（平台活动每人一张；商家券活动每人最多 per_user_limit 张）';

CREATE TABLE coupon_campaign_audit (
    id              BIGINT NOT NULL,
    campaign_id     BIGINT NOT NULL,
    action_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_id     BIGINT NOT NULL,
    request_id      VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detail_json     JSON NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_campaign_audit_request (request_id),
    KEY idx_coupon_campaign_audit_row (campaign_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='券活动写动作 append-only 审计';

-- CCR-MKT-MERCHANT-COUPON-001（契约 79 / SQL84）：商家券预算账户与预算账本，与 coupon_ledger 分离。
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

-- CCR-MKT-003: 首页运营内容位（热门服务 / 宠友圈热门容器 / 植物&异宠）。
-- 预留 sponsor_merchant_id / sponsor_store_id 供后续商家竞价；V1 不做竞价结算。
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
        target_type IN ('SERVICE_DIRECTORY','SERVICE_CATEGORY','STORE','SERVICE','CAMPAIGN','COMMUNITY','MINIAPP_PATH','NONE','HOT_SERVICE')
        AND (target_type IN ('NONE','HOT_SERVICE') OR target_value IS NOT NULL)
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
