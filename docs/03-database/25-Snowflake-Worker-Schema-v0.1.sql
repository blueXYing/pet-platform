-- PLAT-002 accepted node-state mapping; MySQL 8.0.16+ CHECK enforcement required.
-- Reviewable migration candidate. NOT in pet-boot's automatic Flyway directory.
-- Execute only in an explicitly authorized newly created isolated test database for this phase.
-- No pre-enabled nodes or production initialization assumptions.
CREATE TABLE snowflake_worker_state (
    node_id               SMALLINT UNSIGNED NOT NULL,
    format_identity       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    initialization_ref    VARCHAR(191) NULL,
    owner_incarnation     BINARY(16) NULL,
    fence                 BIGINT NOT NULL DEFAULT 0,
    reserved_through      BIGINT NOT NULL DEFAULT -1,
    grant_start           BIGINT NULL,
    grant_through         BIGINT NULL,
    lease_until           DATETIME(3) NULL,
    created_at            DATETIME(3) NOT NULL,
    updated_at            DATETIME(3) NOT NULL,
    PRIMARY KEY (node_id),
    CONSTRAINT ck_id_node CHECK (node_id BETWEEN 0 AND 1023),
    CONSTRAINT ck_id_format CHECK (format_identity = 'epoch=1767225600000;t=41;n=10;s=12'
        AND OCTET_LENGTH(format_identity) = OCTET_LENGTH('epoch=1767225600000;t=41;n=10;s=12')),
    CONSTRAINT ck_id_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_id_initialization CHECK (enabled = 0 OR
        (initialization_ref IS NOT NULL AND CHAR_LENGTH(TRIM(initialization_ref)) > 0)),
    CONSTRAINT ck_id_fence CHECK (fence >= 0),
    CONSTRAINT ck_id_h CHECK (reserved_through BETWEEN -1 AND 2199023255551),
    CONSTRAINT ck_id_grant_pair CHECK (
        (grant_start IS NULL AND grant_through IS NULL) OR
        (grant_start IS NOT NULL AND grant_through IS NOT NULL AND
         grant_start >= 0 AND grant_start <= grant_through AND grant_through = reserved_through)),
    CONSTRAINT ck_id_owner_pair CHECK (
        (owner_incarnation IS NULL AND lease_until IS NULL) OR
        (owner_incarnation IS NOT NULL AND lease_until IS NOT NULL AND
         grant_start IS NOT NULL AND grant_through IS NOT NULL AND fence > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- CHECK validates a row, NOT monotonicity across updates. H/fence non-regression is enforced
-- by the locked/CAS repository path and deployment permissions/recovery controls.
