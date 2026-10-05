-- MER staff binding delta (STA-B; SQL54). Isolated validation input, NOT default Flyway.
-- Complements the approved contract-52 relations (merchant_member / merchant_member_store_grant /
-- merchant_member_store_action stay unchanged) with the invitation-confirm flow approved by
-- CCR-W2-API-001 staff-identity-binding-proposal §5 (D1=invite-confirm, D2=verify-only catalog).
-- UTC timestamps. The audit table never stores clear-text phone/name (supplement 35 precedent);
-- the invitation row stores the registered phone only because confirm-time phone equality needs
-- it (see contract 54 §3); projections always mask it.
-- No production migration is authorized by this delivery.

CREATE TABLE merchant_member_invitation (
    id             BIGINT       NOT NULL,
    merchant_id    BIGINT       NOT NULL,
    store_id       BIGINT       NOT NULL COMMENT 'target store of the invite; must belong to the merchant',
    phone          VARCHAR(32)  NOT NULL COMMENT 'registered 11-digit mainland mobile; confirm-time equality fact, always masked in projections',
    member_name    VARCHAR(64)  NOT NULL COMMENT 'owner-registered display name for the invitee',
    status         VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INVITED/CANCELED/CONFIRMED',
    invited_by     BIGINT       NOT NULL COMMENT 'owner user id who registered the invite',
    confirmed_by   BIGINT       NULL COMMENT 'session user id at confirm; NULL until CONFIRMED',
    member_id      BIGINT       NULL COMMENT 'merchant_member row created at confirm; NULL until CONFIRMED',
    version        BIGINT       NOT NULL DEFAULT 0,
    pending_marker VARCHAR(20)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING while INVITED; the decimal id once terminal, so at most one live invitation exists per merchant+phone',
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_member_inv_pending (merchant_id, phone, pending_marker),
    KEY idx_mer_member_inv_store (store_id, status),
    CONSTRAINT chk_mer_member_inv_ids CHECK (id > 0 AND merchant_id > 0 AND store_id > 0
        AND invited_by > 0 AND (confirmed_by IS NULL OR confirmed_by > 0)
        AND (member_id IS NULL OR member_id > 0) AND version >= 0),
    CONSTRAINT chk_mer_member_inv_phone CHECK (phone REGEXP '^1[0-9]{10}$'),
    CONSTRAINT chk_mer_member_inv_name CHECK (CHAR_LENGTH(member_name) BETWEEN 1 AND 64),
    CONSTRAINT chk_mer_member_inv_status CHECK (status IN ('INVITED', 'CANCELED', 'CONFIRMED')),
    CONSTRAINT chk_mer_member_inv_marker CHECK (
        (status = 'INVITED' AND pending_marker = 'PENDING')
        OR (status IN ('CANCELED', 'CONFIRMED') AND pending_marker = CAST(id AS CHAR))),
    CONSTRAINT chk_mer_member_inv_confirm CHECK (
        (status = 'CONFIRMED' AND confirmed_by IS NOT NULL AND member_id IS NOT NULL)
        OR (status IN ('INVITED', 'CANCELED') AND confirmed_by IS NULL AND member_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Actions the invitee consents to at confirm (D1-a confirm page shows this list); copied into
-- merchant_member_store_action inside the confirm transaction. V1 approved catalog: the single
-- verification action (D2); the application rejects anything outside it.
CREATE TABLE merchant_member_invitation_action (
    id            BIGINT       NOT NULL,
    invitation_id BIGINT       NOT NULL,
    action_code   VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_member_inv_action (invitation_id, action_code),
    CONSTRAINT chk_mer_member_inv_action_ids CHECK (id > 0 AND invitation_id > 0),
    CONSTRAINT chk_mer_member_inv_action_code CHECK (action_code REGEXP '^[a-z0-9][a-z0-9.-]{0,99}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only binding audit (supplement 35 shape): one row per command success, bound to the
-- supplement-23 request key; no clear-text phone or name, no canonical parameter bytes.
CREATE TABLE merchant_member_audit (
    id             BIGINT       NOT NULL,
    actor_type     VARCHAR(24)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id       BIGINT       NOT NULL,
    action_code    VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_id    BIGINT       NOT NULL,
    store_id       BIGINT       NOT NULL,
    member_id      BIGINT       NULL,
    invitation_id  BIGINT       NULL,
    from_status    VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status      VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_version   BIGINT       NULL,
    to_version     BIGINT       NOT NULL,
    request_key    VARBINARY(1024) NOT NULL,
    request_id     VARBINARY(512)  NOT NULL,
    trace_id       VARCHAR(128) NULL,
    occurred_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_member_audit_request (request_key),
    KEY idx_mer_member_audit_member (member_id, occurred_at),
    KEY idx_mer_member_audit_store (store_id, occurred_at),
    CONSTRAINT chk_mer_member_audit_ids CHECK (id > 0 AND actor_id > 0 AND merchant_id > 0
        AND store_id > 0 AND (member_id IS NULL OR member_id > 0)
        AND (invitation_id IS NULL OR invitation_id > 0)
        AND (from_version IS NULL OR from_version >= 0) AND to_version >= 0),
    CONSTRAINT chk_mer_member_audit_actor CHECK (actor_type = 'USER'),
    CONSTRAINT chk_mer_member_audit_action CHECK (action_code IN (
        'merchant.staff-member.invite',
        'merchant.staff-member.invitation.cancel',
        'merchant.staff-member.confirm',
        'merchant.staff-member.disable',
        'merchant.staff-member.enable',
        'merchant.staff-member.grant-actions',
        'merchant.staff-member.revoke-store')),
    CONSTRAINT chk_mer_member_audit_status CHECK (
        from_status IS NULL OR from_status IN
        ('INVITED', 'CANCELED', 'CONFIRMED', 'ENABLED', 'DISABLED', 'REVOKED')),
    CONSTRAINT chk_mer_member_audit_to_status CHECK (to_status IN
        ('INVITED', 'CANCELED', 'CONFIRMED', 'ENABLED', 'DISABLED', 'REVOKED')),
    CONSTRAINT chk_mer_member_audit_request CHECK (OCTET_LENGTH(request_key) BETWEEN 1 AND 1024
        AND OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_mer_member_audit_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
