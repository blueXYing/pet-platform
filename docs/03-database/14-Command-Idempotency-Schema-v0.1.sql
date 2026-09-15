-- Pet Platform V1.0 command idempotency schema v0.1
-- MySQL 8.0+. Physical implementation of the approved supplement 23 §3~§7 binding record.
-- Technical infrastructure only; business SSOT remains the source of product rules.

CREATE TABLE IF NOT EXISTS command_idempotency (
    id                 BIGINT        NOT NULL COMMENT 'Snowflake PK',
    request_key        VARCHAR(512)  NOT NULL COMMENT 'namespace|actorType|actorId|scope|requestId',
    canonical_version  VARCHAR(16)   NOT NULL COMMENT 'canonical-v1',
    params_sha256      CHAR(64)      NOT NULL COMMENT 'identifier only; bytes are the equality fact',
    params_canonical   MEDIUMBLOB    NOT NULL COMMENT 'protected canonical bytes',
    status             VARCHAR(16)   NOT NULL COMMENT 'RESERVED/SUCCEEDED',
    receipt_json       JSON          NULL COMMENT 'first minimal success receipt',
    created_at         DATETIME(3)   NOT NULL,
    succeeded_at       DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_command_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Supplement 23 command binding; never auto-deleted';
