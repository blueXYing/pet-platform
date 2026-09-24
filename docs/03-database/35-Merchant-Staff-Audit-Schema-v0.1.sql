-- MER staff audit: approved 2026-09-24. Isolated validation input, NOT default Flyway.
-- Existing merchant_staff and merchant_command_idempotency remain unchanged.
-- UTC timestamps; no clear-text phone/name request snapshot or token in this audit.
CREATE TABLE merchant_staff_audit (
    id BIGINT NOT NULL,
    actor_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    action_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    request_key VARBINARY(1024) NOT NULL,
    request_id VARBINARY(512) NOT NULL,
    trace_id VARCHAR(128) NULL,
    from_employment_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_employment_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_service_enabled TINYINT NULL,
    to_service_enabled TINYINT NOT NULL,
    from_version BIGINT NULL,
    to_version BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mer_staff_audit_request (request_key),
    KEY idx_mer_staff_audit_target (staff_id,occurred_at),
    KEY idx_mer_staff_audit_store (store_id,occurred_at),
    CONSTRAINT chk_mer_staff_audit_ids CHECK(id>0 AND actor_id>0 AND merchant_id>0 AND store_id>0 AND staff_id>0),
    CONSTRAINT chk_mer_staff_audit_actor CHECK(actor_type='USER'),
    CONSTRAINT chk_mer_staff_audit_action CHECK(action_code IN ('merchant.staff.create','merchant.staff.update','merchant.staff.enable')),
    CONSTRAINT chk_mer_staff_audit_employment CHECK((from_employment_status IS NULL OR from_employment_status IN ('ACTIVE','INACTIVE')) AND to_employment_status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT chk_mer_staff_audit_enabled CHECK((from_service_enabled IS NULL OR from_service_enabled IN (0,1)) AND to_service_enabled IN (0,1)),
    CONSTRAINT chk_mer_staff_audit_version CHECK((from_version IS NULL OR from_version>=0) AND to_version>=0),
    CONSTRAINT chk_mer_staff_audit_request CHECK(OCTET_LENGTH(request_key) BETWEEN 1 AND 1024 AND OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_mer_staff_audit_trace CHECK(trace_id IS NULL OR OCTET_LENGTH(trace_id)<=128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
