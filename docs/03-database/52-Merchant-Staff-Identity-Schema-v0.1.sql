-- MER staff login-identity relations: approved supplement-27 storage design §2 (merchant_member /
-- merchant_member_store_grant / action grants). Contract 52 read kernel; isolated validation
-- input, NOT default Flyway. No production migration is authorized by this PR.
-- Read-only in this slice: member binding and grant writers arrive with the approved binding CCR
-- (STA-01); seeds are test fixtures and never binding evidence.
-- UTC timestamps; no phone, name, invitation or session token is stored in these relations.

CREATE TABLE merchant_member (
    id          BIGINT      NOT NULL,
    merchant_id BIGINT      NOT NULL COMMENT 'same merchant as every granted store',
    user_id     BIGINT      NOT NULL COMMENT 'real MINIAPP user; owner_user_id is never duplicated as a member row',
    status      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ENABLED/DISABLED/REVOKED',
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_member_merchant_user (merchant_id, user_id),
    KEY idx_mer_member_user (user_id, status),
    CONSTRAINT chk_mer_member_ids CHECK (id > 0 AND merchant_id > 0 AND user_id > 0 AND version >= 0),
    CONSTRAINT chk_mer_member_status CHECK (status IN ('ENABLED', 'DISABLED', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_member_store_grant (
    id         BIGINT      NOT NULL,
    member_id  BIGINT      NOT NULL,
    store_id   BIGINT      NOT NULL COMMENT 'must belong to the member merchant',
    staff_id   BIGINT      NULL COMMENT 'display-only merchant_staff reference; must belong to the same store when present',
    status     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ENABLED/REVOKED',
    version    BIGINT      NOT NULL DEFAULT 0 COMMENT 'bumped on grant or action change; feeds authzVersion',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_member_grant_store (member_id, store_id),
    KEY idx_mer_member_grant_staff (staff_id),
    CONSTRAINT chk_mer_member_grant_ids CHECK (id > 0 AND member_id > 0 AND store_id > 0
        AND version >= 0 AND (staff_id IS NULL OR staff_id > 0)),
    CONSTRAINT chk_mer_member_grant_status CHECK (status IN ('ENABLED', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_member_store_action (
    id          BIGINT       NOT NULL,
    member_id   BIGINT       NOT NULL,
    store_id    BIGINT       NOT NULL,
    action_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'approved catalog code; no row implies no permission',
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_member_action (member_id, store_id, action_code),
    KEY idx_mer_member_action_code (store_id, action_code),
    CONSTRAINT chk_mer_member_action_ids CHECK (id > 0 AND member_id > 0 AND store_id > 0),
    CONSTRAINT chk_mer_member_action_code CHECK (action_code REGEXP '^[a-z0-9][a-z0-9.-]{0,99}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
