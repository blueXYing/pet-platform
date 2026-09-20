-- CCR-MER-PRIVATE-001. MySQL 8.4, UTC DATETIME(3), utf8mb4 unless an ASCII binary field is stated.
-- Private object keys and grants never appear in public asset_registry.

CREATE TABLE private_asset (
    id BIGINT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    purpose VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_object_version_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_version_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    media_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    bytes BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    scan_provider_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    scan_result_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    ready_at DATETIME(3) NULL,
    retired_at DATETIME(3) NULL,
    UNIQUE KEY uk_private_asset_source_object (source_object_key),
    UNIQUE KEY uk_private_asset_object (object_key),
    KEY idx_private_asset_owner_status (owner_user_id,status,id),
    CHECK (id > 0 AND owner_user_id > 0),
    CHECK (status IN ('UPLOADING','SCANNING','READY','REJECTED','QUARANTINED','RETIRED')),
    CHECK (status <> 'READY' OR (
        object_version_ref IS NOT NULL AND object_sha256 IS NOT NULL
        AND media_type IN ('image/jpeg','image/png') AND bytes BETWEEN 1 AND 10485760
    ))
) ENGINE=InnoDB;

CREATE TABLE private_asset_upload_request (
    id BIGINT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    request_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    asset_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_private_asset_upload_request (owner_user_id,request_id),
    UNIQUE KEY uk_private_asset_upload_asset (asset_id),
    CHECK (id > 0 AND owner_user_id > 0 AND asset_id > 0)
) ENGINE=InnoDB;

CREATE TABLE private_asset_read_grant (
    id BIGINT PRIMARY KEY,
    token_digest BINARY(32) NOT NULL,
    token_proof BINARY(32) NOT NULL,
    token_key_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    material_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id_digest BINARY(32) NOT NULL,
    session_generation BIGINT NOT NULL,
    purpose_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_protected VARBINARY(2048) NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    authz_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    UNIQUE KEY uk_private_asset_grant_digest (token_digest),
    UNIQUE KEY uk_private_asset_grant_request (operator_id,request_id),
    KEY idx_private_asset_grant_asset_time (asset_id,issued_at),
    CHECK (id > 0 AND asset_id > 0 AND application_id > 0 AND revision_id > 0 AND material_id > 0),
    CHECK (status IN ('ISSUED','CONSUMED','EXPIRED','REVOKED')),
    CHECK (expires_at = TIMESTAMPADD(MINUTE,5,issued_at))
) ENGINE=InnoDB;

CREATE TABLE private_asset_read_audit (
    id BIGINT PRIMARY KEY,
    grant_id BIGINT NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    purpose_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authz_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    KEY idx_private_asset_audit_grant (grant_id,occurred_at),
    CHECK (id > 0 AND grant_id > 0 AND application_id > 0 AND revision_id > 0 AND asset_id > 0),
    CHECK (action IN ('ISSUE','CONSUME') AND result IN ('STARTED','SUCCESS','DENIED','GONE','FAILED'))
) ENGINE=InnoDB;
