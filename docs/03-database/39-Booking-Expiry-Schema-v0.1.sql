-- Development/review migration only; no automatic production execution.
-- Keep USER audit provenance for existing holds; expiry records the actual SYSTEM actor.
ALTER TABLE schedule_reservation_audit
    MODIFY COLUMN actor_user_id BIGINT NULL,
    ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'USER';

-- execute_at changes on retry; preserve initial scheduling for idempotent submissions.
ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL;
