-- Apply after Schema06 in an isolated/explicitly approved database only.
-- No automatic migration, legacy backfill or production enablement.
CREATE TABLE schedule_store_capacity_guard (
    store_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT chk_capacity_guard_identity CHECK (store_id > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE schedule_availability_window
    ADD COLUMN window_kind VARCHAR(16) NOT NULL DEFAULT 'GENERAL';
-- NULL identifies legacy rows requiring evidence-based recovery before protected use.
ALTER TABLE schedule_reservation ADD COLUMN user_id BIGINT NULL;

CREATE TABLE schedule_reservation_claim (
    id BIGINT NOT NULL PRIMARY KEY,
    reservation_id BIGINT NOT NULL,
    window_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_claim_reservation_kind (reservation_id, kind),
    KEY idx_claim_store_reservation (store_id, reservation_id),
    KEY idx_claim_window_time (window_id, start_at, end_at),
    CONSTRAINT chk_claim_interval CHECK (end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE order_staff_assignment
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD KEY idx_assignment_current_order (is_current, order_id);
-- idx_order_store_stage_created already supports store-prefix enumeration; verify EXPLAIN.
