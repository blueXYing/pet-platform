-- MER-001 approved agreement storage (supplement 27), MySQL 8.0+.
-- Reviewed implementation input only; not installed in default Flyway or enabled in production.
-- All DATETIME(3) values are UTC. Published version content is immutable through application APIs.
-- Application review/member tables and production publishing are NOT supplied by this script.

CREATE TABLE merchant_agreement_version (
    id                        BIGINT NOT NULL,
    agreement_version         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content                   TEXT NOT NULL,
    content_sha256            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at              DATETIME(3) NOT NULL,
    published_by_operator_id  BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_agreement_version (agreement_version),
    CONSTRAINT chk_mer_agreement_version_ids CHECK (id > 0 AND published_by_operator_id > 0),
    CONSTRAINT chk_mer_agreement_version_name CHECK (
        REGEXP_LIKE(agreement_version, '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$', 'c')
        AND NOT REGEXP_LIKE(agreement_version, '[^A-Za-z0-9._-]', 'c')
    ),
    CONSTRAINT chk_mer_agreement_content CHECK (OCTET_LENGTH(content) BETWEEN 1 AND 65535),
    CONSTRAINT chk_mer_agreement_content_hash CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_agreement_current (
    agreement_key         VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agreement_version_id  BIGINT NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    updated_at            DATETIME(3) NOT NULL,
    PRIMARY KEY (agreement_key),
    CONSTRAINT chk_mer_agreement_current_key CHECK (agreement_key = 'MERCHANT' AND OCTET_LENGTH(agreement_key) = 8),
    CONSTRAINT chk_mer_agreement_current_version CHECK (agreement_version_id > 0 AND version >= 0),
    CONSTRAINT fk_mer_agreement_current_version FOREIGN KEY (agreement_version_id)
        REFERENCES merchant_agreement_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_agreement_acceptance (
    id                    BIGINT NOT NULL,
    merchant_id           BIGINT NOT NULL,
    agreement_version_id  BIGINT NOT NULL,
    accepted_by_user_id   BIGINT NOT NULL,
    accepted_at           DATETIME(3) NOT NULL,
    content_sha256        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_agreement_acceptance (merchant_id, agreement_version_id),
    CONSTRAINT chk_mer_agreement_acceptance_ids CHECK (
        id > 0 AND merchant_id > 0 AND agreement_version_id > 0 AND accepted_by_user_id > 0
    ),
    CONSTRAINT chk_mer_agreement_acceptance_hash CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT fk_mer_agreement_acceptance_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT fk_mer_agreement_acceptance_version FOREIGN KEY (agreement_version_id)
        REFERENCES merchant_agreement_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Merchant-owned binding; never query the user module's command_idempotency table.
-- Binary key accommodates a 512-byte requestId plus fixed namespace/actor/scope framing,
-- and preserves exact bytes, including case and permitted trailing spaces.
CREATE TABLE merchant_command_idempotency (
    id                 BIGINT NOT NULL,
    request_key        VARBINARY(1024) NOT NULL,
    canonical_version  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_sha256      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_canonical   MEDIUMBLOB NOT NULL,
    status             VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json       JSON NULL,
    trace_id           VARCHAR(128) NULL,
    created_at         DATETIME(3) NOT NULL,
    succeeded_at       DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_command_request (request_key),
    CONSTRAINT chk_mer_command_id CHECK (id > 0),
    CONSTRAINT chk_mer_command_key CHECK (OCTET_LENGTH(request_key) BETWEEN 1 AND 1024),
    CONSTRAINT chk_mer_command_params CHECK (OCTET_LENGTH(params_canonical) BETWEEN 1 AND 65536),
    CONSTRAINT chk_mer_command_hash CHECK (REGEXP_LIKE(params_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT chk_mer_command_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128),
    CONSTRAINT chk_mer_command_receipt CHECK (
        (status = 'RESERVED' AND OCTET_LENGTH(status) = 8 AND receipt_json IS NULL AND succeeded_at IS NULL)
        OR (status = 'SUCCEEDED' AND OCTET_LENGTH(status) = 9 AND receipt_json IS NOT NULL AND succeeded_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
