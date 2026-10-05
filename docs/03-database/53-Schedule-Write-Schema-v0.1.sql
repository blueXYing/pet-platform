-- Pet Platform V1.0 schedule write-side schema v0.1 (SCH-004; SQL53)
-- MySQL 8.0+. Apply after Schema06 (06号) and 37-Reservation-Protection-Foundation-Schema
-- (37号) in an isolated/explicitly approved database only.
-- No automatic migration, legacy backfill or production enablement.
-- window_kind on schedule_availability_window already exists via 37号; no window/availability
-- column changes are required for the write side.

-- SCHC-2 capability set header (34号 Schedule-Protection-Storage §3): one monotonic BIGINT
-- version per staff. Absent header + absent details reads as version "0"; details without a
-- header are LEGACY_UNVERSIONED and must be inventoried/backfilled before protected use.
CREATE TABLE staff_capability_set (
    staff_id   BIGINT      NOT NULL,
    store_id   BIGINT      NOT NULL,
    version    BIGINT      NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (staff_id),
    KEY idx_capability_set_store (store_id),
    CONSTRAINT chk_capability_set_identity CHECK (staff_id > 0 AND store_id > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='SCHC-2 capability set header; CAS target for full-replacement PUT';

-- SCHW-D9 append-only write audit (34号 Schedule-Protection-Storage §6): one row per affected
-- target per request; reason is mandatory for close/batch-close/staff-window-close actions and
-- for availability/capability reductions (application-enforced for the conditional cases).
CREATE TABLE schedule_write_action (
    id             BIGINT        NOT NULL,
    target_type    VARCHAR(16)   NOT NULL COMMENT 'WINDOW/STAFF_WINDOW/CAPABILITY_SET',
    target_id      BIGINT        NOT NULL,
    merchant_id    BIGINT        NOT NULL,
    store_id       BIGINT        NOT NULL,
    action         VARCHAR(32)   NOT NULL COMMENT 'WINDOW_CREATE/WINDOW_UPDATE/WINDOW_CLOSE/WINDOW_OPEN/WINDOW_BATCH_CLOSE/STAFF_WINDOW_CREATE/STAFF_WINDOW_UPDATE/STAFF_WINDOW_CLOSE/STAFF_WINDOW_OPEN/CAPABILITY_REPLACE',
    actor_user_id  BIGINT        NOT NULL,
    request_id     VARBINARY(512) NOT NULL,
    trace_id       VARCHAR(128)  NULL,
    reason         VARCHAR(500)  NULL,
    version_before BIGINT        NULL,
    version_after  BIGINT        NOT NULL,
    occurred_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_write_action (request_id, target_type, target_id),
    KEY idx_write_action_target (target_type, target_id, occurred_at),
    KEY idx_write_action_store (store_id, occurred_at),
    CONSTRAINT chk_write_action_type CHECK (target_type IN ('WINDOW', 'STAFF_WINDOW', 'CAPABILITY_SET')),
    CONSTRAINT chk_write_action_action CHECK (action IN (
        'WINDOW_CREATE', 'WINDOW_UPDATE', 'WINDOW_CLOSE', 'WINDOW_OPEN', 'WINDOW_BATCH_CLOSE',
        'STAFF_WINDOW_CREATE', 'STAFF_WINDOW_UPDATE', 'STAFF_WINDOW_CLOSE',
        'STAFF_WINDOW_OPEN', 'CAPABILITY_REPLACE')),
    CONSTRAINT chk_write_action_reason CHECK (reason IS NULL OR CHAR_LENGTH(reason) BETWEEN 1 AND 500),
    CONSTRAINT chk_write_action_close_reason CHECK (
        action NOT IN ('WINDOW_CLOSE', 'WINDOW_BATCH_CLOSE', 'STAFF_WINDOW_CLOSE')
        OR reason IS NOT NULL),
    CONSTRAINT chk_write_action_versions CHECK (
        (version_before IS NULL OR version_before >= 0) AND version_after >= 0),
    CONSTRAINT chk_write_action_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_write_action_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='排期写侧 append-only 审计（关闭/批量/减员原因必填）';
