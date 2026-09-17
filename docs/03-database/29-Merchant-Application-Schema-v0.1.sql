-- MER-001 merchant application/review storage contract, MySQL 8.0.16+.
--
-- This is an isolated DDL contract review artifact.  It is deliberately not a
-- Flyway migration and must run only after the tables in SQL06 (merchant) and
-- the merchant-owned agreement/command tables have been provisioned.
-- All DATETIME(3) values are UTC.  IDs are Snowflake BIGINT values in storage
-- and are serialized as decimal strings by HTTP contracts.
--
-- The first slice has one immutable HMAC policy row.  The secret key itself is
-- held by the approved secret service; it is never stored in this schema.
-- Online key rotation is not part of this contract.  Changing the policy while
-- active claims exist is a fail-closed operational error, not a new digest.

CREATE TABLE merchant_subject_lookup_policy (
    policy_slot       TINYINT UNSIGNED NOT NULL,
    key_version       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    algorithm         VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (policy_slot),
    UNIQUE KEY uk_mer_subject_policy_version (key_version),
    UNIQUE KEY uk_mer_subject_policy_slot_version (policy_slot, key_version),
    CONSTRAINT chk_mer_subject_policy_slot CHECK (policy_slot = 1),
    CONSTRAINT chk_mer_subject_policy_algorithm CHECK (algorithm = 'HMAC-SHA-256'),
    CONSTRAINT chk_mer_subject_policy_version CHECK (
        REGEXP_LIKE(key_version, '^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$', 'c')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application (
    id                         BIGINT NOT NULL,
    application_no             VARCHAR(22) CHARACTER SET ascii COLLATE ascii_bin NULL,
    owner_user_id              BIGINT NOT NULL,
    reserved_merchant_id       BIGINT NOT NULL,
    status                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_revision_id        BIGINT NULL,
    submitted_revision_id      BIGINT NULL,
    current_review_task_id     BIGINT NULL,
    current_decision_id        BIGINT NULL,
    current_decision_type      VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    review_audit_id            BIGINT NULL,
    current_credit_claim_id    BIGINT NULL,
    current_credit_claim_type  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    current_credit_claim_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    current_identity_claim_id BIGINT NULL,
    current_identity_claim_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    current_identity_claim_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    subject_verification_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version                    BIGINT NOT NULL DEFAULT 0,
    submitted_at               DATETIME(3) NULL,
    reviewed_at                DATETIME(3) NULL,
    created_at                 DATETIME(3) NOT NULL,
    updated_at                 DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_application_no (application_no),
    UNIQUE KEY uk_mer_application_owner (owner_user_id),
    UNIQUE KEY uk_mer_application_reserved_merchant (reserved_merchant_id),
    UNIQUE KEY uk_mer_application_id_reserved_merchant (id, reserved_merchant_id),
    KEY idx_mer_application_status (status, id),
    KEY idx_mer_application_owner_status (owner_user_id, status),
    CONSTRAINT chk_mer_application_ids CHECK (
        id > 0 AND owner_user_id > 0 AND reserved_merchant_id > 0
    ),
    CONSTRAINT chk_mer_application_status CHECK (
        status IN ('DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_mer_application_version CHECK (version >= 0),
    CONSTRAINT chk_mer_application_subject_status CHECK (
        subject_verification_status IN ('NOT_STARTED', 'SUBJECT_VERIFICATION_PENDING', 'VERIFIED')
    ),
    CONSTRAINT chk_mer_application_no_shape CHECK (
        application_no IS NULL
        OR REGEXP_LIKE(application_no, '^SQ[0-9]{8}[A-Za-z0-9]{8}$', 'c')
    ),
    CONSTRAINT chk_mer_application_decision_pair CHECK (
        (current_decision_id IS NULL AND current_decision_type IS NULL)
        OR (current_decision_id IS NOT NULL AND current_decision_type IS NOT NULL)
    ),
    CONSTRAINT chk_mer_application_claim_pointer_pair CHECK (
        (current_credit_claim_id IS NULL
            AND current_credit_claim_type IS NULL
            AND current_credit_claim_status IS NULL)
        OR (current_credit_claim_id IS NOT NULL
            AND current_credit_claim_type = 'CREDIT_CODE'
            AND current_credit_claim_status = 'ACTIVE')
    ),
    CONSTRAINT chk_mer_application_identity_pointer_pair CHECK (
        (current_identity_claim_id IS NULL
            AND current_identity_claim_type IS NULL
            AND current_identity_claim_status IS NULL)
        OR (current_identity_claim_id IS NOT NULL
            AND current_identity_claim_type = 'IDENTITY_NUMBER'
            AND current_identity_claim_status = 'ACTIVE')
    ),
    CONSTRAINT chk_mer_application_state_shape CHECK (
        (status = 'DRAFT'
            AND application_no IS NULL
            AND submitted_revision_id IS NULL
            AND current_review_task_id IS NULL
            AND current_decision_id IS NULL
            AND review_audit_id IS NULL
            AND submitted_at IS NULL
            AND reviewed_at IS NULL
            AND subject_verification_status = 'NOT_STARTED')
        OR (status = 'REVIEWING'
            AND application_no IS NOT NULL
            AND submitted_revision_id IS NOT NULL
            AND current_review_task_id IS NOT NULL
            AND current_revision_id = submitted_revision_id
            AND current_decision_id IS NULL
            AND review_audit_id IS NULL
            AND submitted_at IS NOT NULL
            AND reviewed_at IS NULL
            AND subject_verification_status <> 'NOT_STARTED')
        OR (status = 'REJECTED'
            AND application_no IS NOT NULL
            AND submitted_revision_id IS NOT NULL
            AND current_review_task_id IS NOT NULL
            AND current_decision_id IS NOT NULL
            AND current_decision_type IN ('REJECT', 'REQUEST_CORRECTION')
            AND review_audit_id IS NOT NULL
            AND submitted_at IS NOT NULL
            AND reviewed_at IS NOT NULL)
        OR status = 'APPROVED'
    ),
    CONSTRAINT chk_mer_application_approved_projection CHECK (
        status <> 'APPROVED'
        OR (
            application_no IS NOT NULL
            AND current_revision_id IS NOT NULL
            AND submitted_revision_id IS NOT NULL
            AND current_revision_id = submitted_revision_id
            AND current_review_task_id IS NOT NULL
            AND current_decision_id IS NOT NULL
            AND current_decision_type = 'APPROVE'
            AND review_audit_id IS NOT NULL
            AND current_credit_claim_id IS NOT NULL
            AND current_identity_claim_id IS NOT NULL
            AND reviewed_at IS NOT NULL
            AND subject_verification_status = 'VERIFIED'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application_revision (
    id                       BIGINT NOT NULL,
    application_id           BIGINT NOT NULL,
    revision_no              INT UNSIGNED NOT NULL,
    merchant_name            VARCHAR(128) NULL,
    contact_name             VARCHAR(64) NULL,
    contact_phone_protected  VARBINARY(512) NULL,
    email_protected          VARBINARY(512) NULL,
    merchant_type_code       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    city_code                VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    address                  VARCHAR(255) NULL,
    longitude                DECIMAL(10,7) NULL,
    latitude                 DECIMAL(10,7) NULL,
    introduction             VARCHAR(500) NULL,
    canonical_sha256         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_by_user_id       BIGINT NOT NULL,
    created_at               DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_revision_application_no (application_id, revision_no),
    UNIQUE KEY uk_mer_revision_application_id (application_id, id),
    KEY idx_mer_revision_application_created (application_id, created_at, id),
    CONSTRAINT chk_mer_revision_ids CHECK (
        id > 0 AND application_id > 0 AND revision_no > 0 AND created_by_user_id > 0
    ),
    CONSTRAINT chk_mer_revision_coordinates CHECK (
        (longitude IS NULL OR longitude BETWEEN -180.0000000 AND 180.0000000)
        AND (latitude IS NULL OR latitude BETWEEN -90.0000000 AND 90.0000000)
    ),
    CONSTRAINT chk_mer_revision_hash CHECK (
        canonical_sha256 IS NULL OR REGEXP_LIKE(canonical_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT fk_mer_revision_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application_material (
    id                  BIGINT NOT NULL,
    application_id      BIGINT NOT NULL,
    material_type       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    private_asset_id    BIGINT NOT NULL,
    sha256              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type          VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bytes               INT UNSIGNED NOT NULL,
    uploaded_by_user_id BIGINT NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_material_application_asset (application_id, private_asset_id),
    UNIQUE KEY uk_mer_material_application_id (application_id, id),
    UNIQUE KEY uk_mer_material_application_id_hash (application_id, id, sha256),
    UNIQUE KEY uk_mer_material_application_id_type (application_id, id, material_type),
    KEY idx_mer_material_application_type (application_id, material_type, id),
    KEY idx_mer_material_asset_hash (private_asset_id, sha256),
    CONSTRAINT chk_mer_material_ids CHECK (
        id > 0 AND application_id > 0 AND private_asset_id > 0 AND uploaded_by_user_id > 0
    ),
    CONSTRAINT chk_mer_material_type CHECK (
        material_type IN (
            'STORE_PHOTO', 'BUSINESS_LICENSE', 'ID_CARD_FRONT',
            'ID_CARD_BACK', 'INDUSTRY_LICENSE'
        )
    ),
    CONSTRAINT chk_mer_material_file CHECK (
        bytes BETWEEN 1 AND 10485760
        AND media_type IN ('image/jpeg', 'image/png')
    ),
    CONSTRAINT chk_mer_material_hash CHECK (
        REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT fk_mer_material_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application_revision_material (
    application_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    material_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (application_id, revision_id, material_id),
    UNIQUE KEY uk_mer_revision_material_pair (revision_id, material_id),
    UNIQUE KEY uk_mer_revision_material_material_type (
        application_id, revision_id, material_id, material_type
    ),
    UNIQUE KEY uk_mer_revision_material_slot (application_id, revision_id, material_type, position),
    KEY idx_mer_revision_material_material (material_id, revision_id),
    CONSTRAINT fk_mer_revision_material_revision FOREIGN KEY (application_id, revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    CONSTRAINT fk_mer_revision_material_material FOREIGN KEY (application_id, material_id)
        REFERENCES merchant_application_material(application_id, id),
    CONSTRAINT fk_mer_revision_material_material_type FOREIGN KEY (
        application_id, material_id, material_type
    ) REFERENCES merchant_application_material(application_id, id, material_type),
    CONSTRAINT chk_mer_revision_material_type CHECK (
        material_type IN (
            'STORE_PHOTO', 'BUSINESS_LICENSE', 'ID_CARD_FRONT',
            'ID_CARD_BACK', 'INDUSTRY_LICENSE'
        )
    ),
    CONSTRAINT chk_mer_revision_material_position CHECK (
        (material_type = 'STORE_PHOTO' AND position BETWEEN 1 AND 6)
        OR (material_type <> 'STORE_PHOTO' AND position = 1)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_credential_evidence (
    id                       BIGINT NOT NULL,
    application_id           BIGINT NOT NULL,
    revision_id              BIGINT NOT NULL,
    material_id              BIGINT NOT NULL,
    material_type            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_source          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credential_type          VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_name_protected   VARBINARY(1024) NULL,
    identifier_protected     VARBINARY(1024) NULL,
    identifier_lookup_digest BINARY(32) NULL,
    lookup_policy_slot       TINYINT UNSIGNED NULL,
    lookup_key_version       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    valid_from               DATE NULL,
    valid_to                 DATE NULL,
    validity_kind             VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    validity_basis_protected  VARBINARY(512) NULL,
    extractor_name           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    extractor_version        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    raw_evidence_asset_id    BIGINT NULL,
    verified_by_operator_id  BIGINT NULL,
    verification_reason      VARCHAR(500) NULL,
    observed_at              DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_evidence_application_id (application_id, id),
    UNIQUE KEY uk_mer_evidence_material_hash (
        application_id, revision_id, id, material_id, material_sha256
    ),
    UNIQUE KEY uk_mer_evidence_claim_match (
        application_id, revision_id, id, credential_type, identifier_lookup_digest,
        lookup_policy_slot, lookup_key_version, evidence_status
    ),
    UNIQUE KEY uk_mer_evidence_claim_match_any_revision (
        application_id, id, credential_type, identifier_lookup_digest,
        lookup_policy_slot, lookup_key_version, evidence_status
    ),
    KEY idx_mer_evidence_material_status (material_id, evidence_status, id),
    KEY idx_mer_evidence_lookup (credential_type, identifier_lookup_digest),
    CONSTRAINT chk_mer_evidence_ids CHECK (
        id > 0 AND application_id > 0 AND revision_id > 0 AND material_id > 0
    ),
    CONSTRAINT chk_mer_evidence_source CHECK (evidence_source IN ('OCR', 'MANUAL')),
    CONSTRAINT chk_mer_evidence_status CHECK (evidence_status IN ('SUCCEEDED', 'FAILED', 'VERIFIED')),
    CONSTRAINT chk_mer_evidence_credential CHECK (
        credential_type IN ('CREDIT_CODE', 'IDENTITY_NUMBER', 'INDUSTRY_LICENSE')
    ),
    CONSTRAINT chk_mer_evidence_material_type CHECK (
        material_type IN (
            'BUSINESS_LICENSE', 'ID_CARD_FRONT', 'ID_CARD_BACK', 'INDUSTRY_LICENSE'
        )
        AND (
            (credential_type = 'CREDIT_CODE' AND material_type = 'BUSINESS_LICENSE')
            OR (credential_type = 'IDENTITY_NUMBER'
                AND material_type IN ('ID_CARD_FRONT', 'ID_CARD_BACK'))
            OR (credential_type = 'INDUSTRY_LICENSE' AND material_type = 'INDUSTRY_LICENSE')
        )
    ),
    CONSTRAINT chk_mer_evidence_validity CHECK (
        (validity_kind = 'UNKNOWN' AND valid_from IS NULL AND valid_to IS NULL)
        OR (validity_kind = 'DATED' AND valid_from IS NOT NULL AND valid_to IS NOT NULL
            AND valid_from <= valid_to)
        OR (validity_kind = 'LONG_TERM' AND valid_from IS NOT NULL AND valid_to IS NULL
            AND validity_basis_protected IS NOT NULL
            AND OCTET_LENGTH(validity_basis_protected) BETWEEN 1 AND 512)
    ),
    CONSTRAINT chk_mer_evidence_material_hash CHECK (
        REGEXP_LIKE(material_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT chk_mer_evidence_key_fields CHECK (
        (identifier_lookup_digest IS NULL AND lookup_policy_slot IS NULL AND lookup_key_version IS NULL)
        OR (
            identifier_lookup_digest IS NOT NULL
            AND lookup_policy_slot = 1
            AND lookup_key_version IS NOT NULL
        )
    ),
    CONSTRAINT chk_mer_evidence_verification CHECK (
        (evidence_source = 'MANUAL' AND verified_by_operator_id IS NOT NULL
            AND verification_reason IS NOT NULL AND CHAR_LENGTH(TRIM(verification_reason)) > 0)
        OR evidence_source = 'OCR'
    ),
    CONSTRAINT chk_mer_evidence_verified_fields CHECK (
        evidence_status <> 'VERIFIED'
        OR (identifier_protected IS NOT NULL AND identifier_lookup_digest IS NOT NULL
            AND validity_kind <> 'UNKNOWN')
    ),
    CONSTRAINT chk_mer_evidence_dates CHECK (
        valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to
    ),
    CONSTRAINT fk_mer_evidence_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id),
    CONSTRAINT fk_mer_evidence_revision FOREIGN KEY (application_id, revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    CONSTRAINT fk_mer_evidence_revision_material FOREIGN KEY (
        application_id, revision_id, material_id, material_type
    ) REFERENCES merchant_application_revision_material(
        application_id, revision_id, material_id, material_type
    ),
    CONSTRAINT fk_mer_evidence_material FOREIGN KEY (application_id, material_id)
        REFERENCES merchant_application_material(application_id, id),
    CONSTRAINT fk_mer_evidence_material_hash FOREIGN KEY (
        application_id, material_id, material_sha256
    ) REFERENCES merchant_application_material(application_id, id, sha256),
    CONSTRAINT fk_mer_evidence_policy FOREIGN KEY (lookup_policy_slot, lookup_key_version)
        REFERENCES merchant_subject_lookup_policy(policy_slot, key_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_subject_claim (
    id                  BIGINT NOT NULL,
    claim_type          VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lookup_digest       BINARY(32) NOT NULL,
    lookup_policy_slot  TINYINT UNSIGNED NOT NULL DEFAULT 1,
    lookup_key_version  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id      BIGINT NOT NULL,
    revision_id         BIGINT NOT NULL,
    evidence_id         BIGINT NOT NULL,
    evidence_status     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VERIFIED',
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_at          DATETIME(3) NOT NULL,
    released_at         DATETIME(3) NULL,
    active_lookup       VARBINARY(80) GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE'
             THEN CONCAT(claim_type, 0x00, lookup_digest)
             ELSE NULL
        END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_claim_active_lookup (active_lookup),
    UNIQUE KEY uk_mer_claim_application_type (application_id, claim_type, active_lookup),
    UNIQUE KEY uk_mer_claim_application_id_type_status (
        application_id, id, claim_type, status
    ),
    UNIQUE KEY uk_mer_claim_application_id_type_status_revision (
        application_id, id, claim_type, status, revision_id
    ),
    KEY idx_mer_claim_application_status (application_id, status, claim_type),
    KEY idx_mer_claim_evidence (evidence_id),
    CONSTRAINT chk_mer_claim_ids CHECK (
        id > 0 AND application_id > 0 AND revision_id > 0 AND evidence_id > 0
    ),
    CONSTRAINT chk_mer_claim_type CHECK (claim_type IN ('CREDIT_CODE', 'IDENTITY_NUMBER')),
    CONSTRAINT chk_mer_claim_evidence_status CHECK (evidence_status = 'VERIFIED'),
    CONSTRAINT chk_mer_claim_status CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL)
    ),
    CONSTRAINT fk_mer_claim_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id),
    CONSTRAINT fk_mer_claim_revision FOREIGN KEY (application_id, revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    CONSTRAINT fk_mer_claim_evidence FOREIGN KEY (
        application_id, evidence_id, claim_type, lookup_digest,
        lookup_policy_slot, lookup_key_version, evidence_status
    ) REFERENCES merchant_credential_evidence(
        application_id, id, credential_type, identifier_lookup_digest,
        lookup_policy_slot, lookup_key_version, evidence_status
    ),
    CONSTRAINT fk_mer_claim_policy FOREIGN KEY (lookup_policy_slot, lookup_key_version)
        REFERENCES merchant_subject_lookup_policy(policy_slot, key_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application_review_task (
    id                    BIGINT NOT NULL,
    application_id        BIGINT NOT NULL,
    submitted_revision_id BIGINT NOT NULL,
    submission_no         INT UNSIGNED NOT NULL,
    status                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_by_operator_id BIGINT NULL,
    claimed_at            DATETIME(3) NULL,
    closed_at             DATETIME(3) NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    updated_at            DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_task_application_revision (application_id, submitted_revision_id),
    UNIQUE KEY uk_mer_task_application_id (application_id, id),
    UNIQUE KEY uk_mer_task_application_id_revision (application_id, id, submitted_revision_id),
    UNIQUE KEY uk_mer_task_application_id_revision_status (
        application_id, id, submitted_revision_id, status
    ),
    UNIQUE KEY uk_mer_task_application_id_revision_claimant (
        application_id, id, submitted_revision_id, claimed_by_operator_id
    ),
    UNIQUE KEY uk_mer_task_application_submission (application_id, submission_no),
    KEY idx_mer_task_status (status, updated_at, id),
    KEY idx_mer_task_claimant (claimed_by_operator_id, status),
    CONSTRAINT chk_mer_task_ids CHECK (id > 0 AND application_id > 0 AND submitted_revision_id > 0),
    CONSTRAINT chk_mer_task_submission CHECK (submission_no > 0),
    CONSTRAINT chk_mer_task_version CHECK (version >= 0),
    CONSTRAINT chk_mer_task_status CHECK (
        status IN ('AVAILABLE', 'CLAIMED', 'CLOSED')
        AND (
            (status = 'AVAILABLE' AND claimed_by_operator_id IS NULL AND claimed_at IS NULL AND closed_at IS NULL)
            OR (status = 'CLAIMED' AND claimed_by_operator_id IS NOT NULL AND claimed_at IS NOT NULL AND closed_at IS NULL)
            OR (status = 'CLOSED' AND claimed_by_operator_id IS NOT NULL
                AND claimed_at IS NOT NULL AND closed_at IS NOT NULL)
        )
    ),
    CONSTRAINT fk_mer_task_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id),
    CONSTRAINT fk_mer_task_revision FOREIGN KEY (application_id, submitted_revision_id)
        REFERENCES merchant_application_revision(application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application_review_decision (
    id                    BIGINT NOT NULL,
    application_id        BIGINT NOT NULL,
    submitted_revision_id BIGINT NOT NULL,
    task_id               BIGINT NOT NULL,
    decision_type         VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    opinion               VARCHAR(500) NULL,
    internal_note         VARCHAR(500) NULL,
    decided_by_operator_id BIGINT NOT NULL,
    decided_at            DATETIME(3) NOT NULL,
    authz_version         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id            VARBINARY(512) NOT NULL,
    trace_id              VARCHAR(128) NULL,
    credit_evidence_id    BIGINT NULL,
    credit_evidence_type  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    credit_evidence_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    credit_evidence_digest BINARY(32) NULL,
    credit_evidence_policy_slot TINYINT UNSIGNED NULL,
    credit_evidence_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_evidence_id  BIGINT NULL,
    identity_evidence_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_evidence_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_evidence_digest BINARY(32) NULL,
    identity_evidence_policy_slot TINYINT UNSIGNED NULL,
    identity_evidence_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    credit_claim_id       BIGINT NULL,
    credit_claim_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    credit_claim_status   VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_claim_id     BIGINT NULL,
    identity_claim_type   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_claim_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_decision_application_id_type_revision (
        application_id, id, decision_type, submitted_revision_id
    ),
    UNIQUE KEY uk_mer_decision_application_revision_operator (
        application_id, id, submitted_revision_id, decided_by_operator_id
    ),
    UNIQUE KEY uk_mer_decision_task (task_id),
    KEY idx_mer_decision_application_revision (application_id, submitted_revision_id, decided_at, id),
    KEY idx_mer_decision_operator (decided_by_operator_id, decided_at),
    CONSTRAINT chk_mer_decision_ids CHECK (
        id > 0 AND application_id > 0 AND submitted_revision_id > 0
        AND task_id > 0 AND decided_by_operator_id > 0
    ),
    CONSTRAINT chk_mer_decision_type CHECK (
        decision_type IN ('APPROVE', 'REJECT', 'REQUEST_CORRECTION')
    ),
    CONSTRAINT chk_mer_decision_opinion CHECK (
        (decision_type = 'APPROVE' AND (opinion IS NULL OR CHAR_LENGTH(opinion) <= 500))
        OR (decision_type <> 'APPROVE' AND opinion IS NOT NULL
            AND CHAR_LENGTH(opinion) BETWEEN 10 AND 500)
    ),
    CONSTRAINT chk_mer_decision_internal_note CHECK (
        internal_note IS NULL OR CHAR_LENGTH(internal_note) <= 500
    ),
    CONSTRAINT chk_mer_decision_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_mer_decision_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128),
    CONSTRAINT chk_mer_decision_versions CHECK (
        OCTET_LENGTH(authz_version) BETWEEN 1 AND 128
        AND OCTET_LENGTH(scope_version) BETWEEN 1 AND 128
    ),
    CONSTRAINT chk_mer_decision_approval_proofs CHECK (
        (decision_type = 'APPROVE'
            AND credit_evidence_id IS NOT NULL
            AND credit_evidence_type = 'CREDIT_CODE'
            AND credit_evidence_status = 'VERIFIED'
            AND credit_evidence_digest IS NOT NULL
            AND credit_evidence_policy_slot = 1
            AND credit_evidence_key_version IS NOT NULL
            AND identity_evidence_id IS NOT NULL
            AND identity_evidence_type = 'IDENTITY_NUMBER'
            AND identity_evidence_status = 'VERIFIED'
            AND identity_evidence_digest IS NOT NULL
            AND identity_evidence_policy_slot = 1
            AND identity_evidence_key_version IS NOT NULL
            AND credit_claim_id IS NOT NULL
            AND credit_claim_type = 'CREDIT_CODE'
            AND credit_claim_status = 'ACTIVE'
            AND identity_claim_id IS NOT NULL
            AND identity_claim_type = 'IDENTITY_NUMBER'
            AND identity_claim_status = 'ACTIVE')
        OR (decision_type <> 'APPROVE'
            AND credit_evidence_id IS NULL
            AND credit_evidence_type IS NULL
            AND credit_evidence_status IS NULL
            AND credit_evidence_digest IS NULL
            AND credit_evidence_policy_slot IS NULL
            AND credit_evidence_key_version IS NULL
            AND identity_evidence_id IS NULL
            AND identity_evidence_type IS NULL
            AND identity_evidence_status IS NULL
            AND identity_evidence_digest IS NULL
            AND identity_evidence_policy_slot IS NULL
            AND identity_evidence_key_version IS NULL
            AND credit_claim_id IS NULL
            AND credit_claim_type IS NULL
            AND credit_claim_status IS NULL
            AND identity_claim_id IS NULL
            AND identity_claim_type IS NULL
            AND identity_claim_status IS NULL)
    ),
    CONSTRAINT fk_mer_decision_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id),
    CONSTRAINT fk_mer_decision_revision FOREIGN KEY (application_id, submitted_revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    CONSTRAINT fk_mer_decision_task FOREIGN KEY (application_id, task_id)
        REFERENCES merchant_application_review_task(application_id, id),
    CONSTRAINT fk_mer_decision_task_revision_claimant FOREIGN KEY (
        application_id, task_id, submitted_revision_id, decided_by_operator_id
    ) REFERENCES merchant_application_review_task(
        application_id, id, submitted_revision_id, claimed_by_operator_id
    ),
    CONSTRAINT fk_mer_decision_credit_evidence FOREIGN KEY (
        application_id, submitted_revision_id, credit_evidence_id,
        credit_evidence_type, credit_evidence_digest,
        credit_evidence_policy_slot, credit_evidence_key_version, credit_evidence_status
    ) REFERENCES merchant_credential_evidence(
        application_id, revision_id, id, credential_type, identifier_lookup_digest,
        lookup_policy_slot, lookup_key_version, evidence_status
    ),
    CONSTRAINT fk_mer_decision_identity_evidence FOREIGN KEY (
        application_id, submitted_revision_id, identity_evidence_id,
        identity_evidence_type, identity_evidence_digest,
        identity_evidence_policy_slot, identity_evidence_key_version, identity_evidence_status
    ) REFERENCES merchant_credential_evidence(
        application_id, revision_id, id, credential_type, identifier_lookup_digest,
        lookup_policy_slot, lookup_key_version, evidence_status
    ),
    CONSTRAINT fk_mer_decision_credit_claim FOREIGN KEY (
        application_id, credit_claim_id, credit_claim_type,
        credit_claim_status
    ) REFERENCES merchant_subject_claim(
        application_id, id, claim_type, status
    ),
    CONSTRAINT fk_mer_decision_identity_claim FOREIGN KEY (
        application_id, identity_claim_id, identity_claim_type,
        identity_claim_status
    ) REFERENCES merchant_subject_claim(
        application_id, id, claim_type, status
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_application_audit (
    id            BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    revision_id   BIGINT NULL,
    actor_type    VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id      BIGINT NOT NULL,
    action_code   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status   VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_status     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id    VARBINARY(512) NOT NULL,
    trace_id      VARCHAR(128) NULL,
    occurred_at   DATETIME(3) NOT NULL,
    decision_id   BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_audit_application_id (application_id, id),
    UNIQUE KEY uk_mer_audit_application_decision (application_id, id, decision_id),
    KEY idx_mer_audit_application_time (application_id, occurred_at, id),
    KEY idx_mer_audit_action_time (action_code, occurred_at, id),
    CONSTRAINT chk_mer_audit_ids CHECK (id > 0 AND application_id > 0 AND actor_id > 0),
    CONSTRAINT chk_mer_audit_actor CHECK (actor_type IN ('USER', 'PLATFORM_OPERATOR', 'SYSTEM')),
    CONSTRAINT chk_mer_audit_action CHECK (
        action_code IN ('DRAFT_SAVE', 'SUBMIT', 'CLAIM', 'RELEASE', 'MANUAL_VERIFY', 'DECISION')
    ),
    CONSTRAINT chk_mer_audit_status CHECK (
        from_status IN ('DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED')
        AND to_status IN ('DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_mer_audit_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_mer_audit_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128),
    CONSTRAINT chk_mer_audit_decision_link CHECK (
        (action_code = 'DECISION' AND decision_id IS NOT NULL AND revision_id IS NOT NULL)
        OR (action_code <> 'DECISION' AND decision_id IS NULL)
    ),
    CONSTRAINT fk_mer_audit_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id),
    CONSTRAINT fk_mer_audit_revision FOREIGN KEY (application_id, revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    CONSTRAINT fk_mer_audit_decision FOREIGN KEY (
        application_id, decision_id, revision_id, actor_id
    ) REFERENCES merchant_application_review_decision(
        application_id, id, submitted_revision_id, decided_by_operator_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Compatibility projection for type/city.  SQL06 is intentionally untouched.
-- LEGACY rows preserve an existing merchant's pre-existing source; APPROVAL
-- rows are populated atomically from the immutable submitted revision.
CREATE TABLE merchant_profile_compat (
    merchant_id        BIGINT NOT NULL,
    application_id     BIGINT NULL,
    source_revision_id BIGINT NULL,
    merchant_type_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    city_code          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_kind        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    PRIMARY KEY (merchant_id),
    UNIQUE KEY uk_mer_profile_compat_application (application_id),
    KEY idx_mer_profile_compat_city_type (city_code, merchant_type_code, merchant_id),
    CONSTRAINT chk_mer_profile_compat_ids CHECK (merchant_id > 0 AND version >= 0),
    CONSTRAINT chk_mer_profile_compat_source CHECK (
        (source_kind = 'LEGACY' AND application_id IS NULL AND source_revision_id IS NULL)
        OR (source_kind = 'APPLICATION' AND application_id IS NOT NULL AND source_revision_id IS NOT NULL)
    ),
    CONSTRAINT fk_mer_profile_compat_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant(id),
    CONSTRAINT fk_mer_profile_compat_application FOREIGN KEY (application_id)
        REFERENCES merchant_application(id),
    CONSTRAINT fk_mer_profile_compat_reserved_merchant FOREIGN KEY (application_id, merchant_id)
        REFERENCES merchant_application(id, reserved_merchant_id),
    CONSTRAINT fk_mer_profile_compat_revision FOREIGN KEY (application_id, source_revision_id)
        REFERENCES merchant_application_revision(application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The application row points forward to immutable revision/task/decision/audit
-- rows.  Add these FKs only after every table above exists; this is intentional
-- and makes the circular current-pointer relationships executable.
ALTER TABLE merchant_application
    ADD CONSTRAINT fk_mer_application_current_revision
        FOREIGN KEY (id, current_revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    ADD CONSTRAINT fk_mer_application_submitted_revision
        FOREIGN KEY (id, submitted_revision_id)
        REFERENCES merchant_application_revision(application_id, id),
    ADD CONSTRAINT fk_mer_application_current_task
        FOREIGN KEY (id, current_review_task_id, submitted_revision_id)
        REFERENCES merchant_application_review_task(
            application_id, id, submitted_revision_id
        ),
    ADD CONSTRAINT fk_mer_application_current_decision
        FOREIGN KEY (id, current_decision_id, current_decision_type, submitted_revision_id)
        REFERENCES merchant_application_review_decision(
            application_id, id, decision_type, submitted_revision_id
        ),
    ADD CONSTRAINT fk_mer_application_current_credit_claim
        FOREIGN KEY (
            id, current_credit_claim_id, current_credit_claim_type,
            current_credit_claim_status
        ) REFERENCES merchant_subject_claim(
            application_id, id, claim_type, status
        ),
    ADD CONSTRAINT fk_mer_application_current_identity_claim
        FOREIGN KEY (
            id, current_identity_claim_id, current_identity_claim_type,
            current_identity_claim_status
        ) REFERENCES merchant_subject_claim(
            application_id, id, claim_type, status
        ),
    ADD CONSTRAINT fk_mer_application_review_audit
        FOREIGN KEY (id, review_audit_id, current_decision_id)
        REFERENCES merchant_application_audit(application_id, id, decision_id);
