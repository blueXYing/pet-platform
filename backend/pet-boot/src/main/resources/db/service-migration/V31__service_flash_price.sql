-- V31__service_flash_price.sql（CCR-SVC-FLASH-PRICE-001 / 契约77 / SQL73 同构）
-- 服务限时秒杀四表；开关 pet.service.flash-price.enabled 默认关闭，迁移 opt-in。

CREATE TABLE service_flash_price (
    id                 BIGINT NOT NULL,
    service_id         BIGINT NOT NULL,
    merchant_id        BIGINT NOT NULL,
    store_id           BIGINT NOT NULL,
    title              VARCHAR(100) NOT NULL,
    description        VARCHAR(300) NULL,
    price_mode         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DISCOUNT',
    discount_rate      DECIMAL(4,2) NOT NULL,
    flash_price        DECIMAL(18,2) NOT NULL,
    list_price_snapshot DECIMAL(18,2) NOT NULL,
    start_at           DATETIME(3) NOT NULL,
    end_at             DATETIME(3) NOT NULL,
    total_quota        INT NULL,
    per_user_limit     INT NULL,
    sold_count         INT NOT NULL DEFAULT 0,
    status             VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reject_reason      VARCHAR(500) NULL,
    version            INT NOT NULL DEFAULT 1,
    created_by_type    VARCHAR(16) NOT NULL,
    created_by_id      BIGINT NOT NULL,
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flash_price_service_time (service_id, status, start_at, end_at),
    KEY idx_flash_price_store_status (store_id, status, start_at),
    CONSTRAINT chk_flash_price_mode CHECK (price_mode = 'DISCOUNT'),
    CONSTRAINT chk_flash_price_rate CHECK (discount_rate > 0 AND discount_rate < 10),
    CONSTRAINT chk_flash_price_amount CHECK (flash_price > 0 AND flash_price < list_price_snapshot),
    CONSTRAINT chk_flash_price_time CHECK (start_at < end_at),
    CONSTRAINT chk_flash_price_quota CHECK (total_quota IS NULL OR total_quota > 0),
    CONSTRAINT chk_flash_price_per_user CHECK (per_user_limit IS NULL OR per_user_limit > 0),
    CONSTRAINT chk_flash_price_sold CHECK (sold_count >= 0),
    CONSTRAINT chk_flash_price_status CHECK (status IN
        ('DRAFT','PENDING_REVIEW','SCHEDULED','ACTIVE','PAUSED','ENDED','REJECTED','CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务限时价格（价格权威在 service；订单快照引用）';

CREATE TABLE service_flash_price_order (
    id            BIGINT NOT NULL,
    flash_price_id BIGINT NOT NULL,
    order_id      BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    reserved_price DECIMAL(18,2) NOT NULL,
    status        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expire_at     DATETIME(3) NOT NULL,
    reserved_at   DATETIME(3) NOT NULL,
    consumed_at   DATETIME(3) NULL,
    released_at   DATETIME(3) NULL,
    version       INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flash_price_order (order_id),
    KEY idx_flash_price_order_user (flash_price_id, user_id, status),
    KEY idx_flash_price_order_expire (status, expire_at),
    CONSTRAINT chk_flash_price_order_status CHECK (status IN ('RESERVED','CONSUMED','RELEASED')),
    CONSTRAINT chk_flash_price_order_price CHECK (reserved_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='闪价预占/消费/释放台账（UNIQUE order_id）';

CREATE TABLE service_flash_price_audit (
    id BIGINT NOT NULL,
    flash_price_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NULL,
    operator_type VARCHAR(16) NOT NULL,
    operator_id BIGINT NOT NULL,
    request_id VARCHAR(191) NOT NULL,
    trace_id VARCHAR(128) NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flash_price_audit_request (request_id),
    KEY idx_flash_price_audit_price (flash_price_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='闪价 append-only 审计';

CREATE TABLE service_flash_price_command (
    id BIGINT NOT NULL,
    request_key VARCHAR(191) NOT NULL,
    command_type VARCHAR(48) NOT NULL,
    params_sha256 CHAR(64) NOT NULL,
    params_canonical LONGBLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    receipt_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flash_price_command_request (request_key),
    CONSTRAINT chk_flash_price_command_status CHECK (status IN ('IN_PROGRESS','SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='闪价写命令 requestId 幂等表';
