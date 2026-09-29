-- R1/R2/R3 approved 2026-09-29. Isolated QA only; no automatic production migration.
CREATE TABLE order_reschedule_command LIKE order_merchant_command;
CREATE TABLE order_reschedule_record (
 id BIGINT NOT NULL PRIMARY KEY, order_id BIGINT NOT NULL, command_id BIGINT NOT NULL,
 event_id BIGINT NOT NULL, user_id BIGINT NOT NULL, store_id BIGINT NOT NULL, reservation_id BIGINT NOT NULL,
 old_order_version BIGINT NOT NULL, new_order_version BIGINT NOT NULL,
 old_reservation_version BIGINT NOT NULL, new_reservation_version BIGINT NOT NULL,
 old_order_stage VARCHAR(32) NOT NULL, old_confirm_mode VARCHAR(32) NULL,
 old_confirmed_at DATETIME(3) NULL, old_confirm_deadline DATETIME(3) NOT NULL,
 old_start DATETIME(3) NOT NULL, old_end DATETIME(3) NOT NULL,
 old_pickup DATETIME(3) NULL, old_return DATETIME(3) NULL,
 new_start DATETIME(3) NOT NULL, new_end DATETIME(3) NOT NULL,
 new_pickup DATETIME(3) NULL, new_return DATETIME(3) NULL,
 rescheduled_at DATETIME(3) NOT NULL, new_confirm_deadline DATETIME(3) NOT NULL,
 from_round INT NOT NULL, to_round INT NOT NULL, schedule_change_id BIGINT NOT NULL,
 verification_fence_id BIGINT NOT NULL, old_task_outcome VARCHAR(16) NOT NULL,
 UNIQUE KEY uk_reschedule_order(order_id), UNIQUE KEY uk_reschedule_command(command_id),
 UNIQUE KEY uk_reschedule_event(event_id), UNIQUE KEY uk_reschedule_schedule(schedule_change_id),
 CONSTRAINT chk_reschedule_round CHECK(from_round=0 AND to_round=1),
 CONSTRAINT chk_reschedule_versions CHECK(new_order_version=old_order_version+1 AND new_reservation_version=old_reservation_version+1),
 CONSTRAINT chk_reschedule_deadline CHECK(new_confirm_deadline=TIMESTAMPADD(MINUTE,30,rescheduled_at)),
 CONSTRAINT chk_reschedule_intervals CHECK(old_end>old_start AND new_end>new_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE schedule_reservation_change (
 id BIGINT NOT NULL PRIMARY KEY, reservation_id BIGINT NOT NULL, order_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL, command_id BIGINT NOT NULL, old_version BIGINT NOT NULL, new_version BIGINT NOT NULL,
 old_snapshot JSON NOT NULL, new_snapshot JSON NOT NULL, changed_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_reservation_change_version(reservation_id,new_version), UNIQUE KEY uk_reservation_change_command(command_id),
 CONSTRAINT chk_reservation_change_version CHECK(new_version=old_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- SQL45's first unnamed CHECK on a freshly installed canonical schema has this name.
-- Existing installations MUST verify SHOW CREATE TABLE and the exact check expression first.
-- Do not run against a database with a different name/expression; adapt only the approved round check.
ALTER TABLE order_merchant_decision DROP CHECK order_merchant_decision_chk_1,
 ADD CONSTRAINT chk_merchant_confirm_round CHECK(confirm_round IN (0,1));
