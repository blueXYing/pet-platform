-- Approved K1/K2. Manual isolated-QA migration only, never startup DDL.
-- No verified historical staff binding source exists in this slice: refuse all legacy rows.
CREATE TABLE verification_completion_migration_gate (legacy_rows BIGINT NOT NULL CHECK (legacy_rows=0));
INSERT INTO verification_completion_migration_gate SELECT (SELECT COUNT(*) FROM verification_record)+(SELECT COUNT(*) FROM verification_attempt);
DROP TABLE verification_completion_migration_gate;
ALTER TABLE verification_record
 MODIFY operator_staff_id BIGINT NULL,
 ADD operator_type VARCHAR(32) NOT NULL, ADD operator_id BIGINT NOT NULL,
 ADD membership_kind VARCHAR(16) NOT NULL, ADD command_id BIGINT NOT NULL,
 ADD credential_id BIGINT NOT NULL, ADD attempt_id BIGINT NOT NULL, ADD order_version BIGINT NOT NULL,
 DROP INDEX uk_verification_request, ADD UNIQUE KEY uk_verification_command(command_id),
 ADD CONSTRAINT chk_verification_actor CHECK ((membership_kind='OWNER' AND operator_type='USER' AND operator_staff_id IS NULL) OR (membership_kind='STAFF' AND operator_type='MERCHANT_STAFF' AND operator_staff_id IS NOT NULL AND operator_id=operator_staff_id));
ALTER TABLE verification_attempt
 MODIFY operator_staff_id BIGINT NULL,
 ADD operator_type VARCHAR(32) NOT NULL, ADD operator_id BIGINT NOT NULL,
 ADD membership_kind VARCHAR(16) NOT NULL, ADD command_id BIGINT NOT NULL,
 DROP INDEX uk_verification_attempt_request, ADD UNIQUE KEY uk_verification_attempt_command(command_id),
 ADD CONSTRAINT chk_verification_attempt_actor CHECK ((membership_kind='OWNER' AND operator_type='USER' AND operator_staff_id IS NULL) OR (membership_kind='STAFF' AND operator_type='MERCHANT_STAFF' AND operator_staff_id IS NOT NULL AND operator_id=operator_staff_id));
ALTER TABLE pet_order ADD aftersale_status VARCHAR(32) NULL;
CREATE TABLE order_verification_commit (
 order_id BIGINT PRIMARY KEY,store_id BIGINT NOT NULL,verification_id BIGINT NOT NULL,credential_id BIGINT NOT NULL,
 attempt_id BIGINT NOT NULL,command_id BIGINT NOT NULL,operator_id BIGINT NOT NULL,request_id VARCHAR(36) NOT NULL,
 order_version BIGINT NOT NULL,event_id BIGINT NOT NULL,verified_at DATETIME(3) NOT NULL,
 aftersale_id BIGINT NULL,aftersale_status VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_order_verified_id(verification_id),UNIQUE KEY uk_order_verified_command(command_id),UNIQUE KEY uk_order_verified_event(event_id)
) ENGINE=InnoDB;
CREATE TABLE aftersale_verification_proof (
 verification_id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,store_id BIGINT NOT NULL,aftersale_id BIGINT NULL,
 case_version BIGINT NULL,case_status VARCHAR(32) NOT NULL,invalidated BOOLEAN NOT NULL,verified_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_aftersale_verified_order(order_id)
) ENGINE=InnoDB;
