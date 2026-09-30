-- V1/V2 approved. Isolated QA migration only; never automatic production DDL.
CREATE TABLE verification_credential_state (
 order_id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, reservation_id BIGINT NOT NULL,
 epoch BIGINT NOT NULL, current_credential_id BIGINT NULL, version BIGINT NOT NULL,
 locked_until DATETIME(3) NULL, updated_at DATETIME(3) NOT NULL,
 CONSTRAINT chk_vc_state CHECK(epoch>=0 AND version>=0)
) ENGINE=InnoDB;
CREATE TABLE verification_credential (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,reservation_id BIGINT NOT NULL,
 epoch BIGINT NOT NULL,generation BIGINT NOT NULL,confirm_round INT NOT NULL,
 appointment_start DATETIME(3) NOT NULL,appointment_end DATETIME(3) NOT NULL,pickup_start DATETIME(3) NULL,return_start DATETIME(3) NULL,
 lookup_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,lookup_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 code_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,code_cipher BLOB NOT NULL,
 issued_at DATETIME(3) NOT NULL,expires_at DATETIME(3) NOT NULL,invalidated_at DATETIME(3) NULL,invalidated_reason VARCHAR(32) NULL,
 UNIQUE KEY uk_vc_generation(order_id,generation),UNIQUE KEY uk_vc_digest(lookup_key_id,lookup_hash),
 CONSTRAINT chk_vc_expiry CHECK(expires_at=TIMESTAMPADD(MINUTE,5,issued_at)),
 CONSTRAINT chk_vc_epoch CHECK(epoch>=0 AND generation>0 AND confirm_round IN(0,1)),
 CONSTRAINT chk_vc_invalidated CHECK((invalidated_at IS NULL AND invalidated_reason IS NULL) OR (invalidated_at IS NOT NULL AND invalidated_reason IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE verification_reschedule_fence (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,reservation_id BIGINT NOT NULL,store_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 reschedule_id BIGINT NOT NULL,old_epoch BIGINT NOT NULL,new_epoch BIGINT NOT NULL,invalidated_generation BIGINT NULL,
 rescheduled_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_vc_reschedule(reschedule_id),UNIQUE KEY uk_vc_order_fence(order_id),
 CONSTRAINT chk_vc_fence_epoch CHECK(new_epoch=old_epoch+1 AND old_epoch=0)
) ENGINE=InnoDB;
CREATE TABLE verification_credential_command (
 id BIGINT PRIMARY KEY,command_namespace VARBINARY(64) NOT NULL,actor_type VARBINARY(32) NOT NULL,actor_id BIGINT NOT NULL,
 authority_scope VARBINARY(64) NOT NULL,request_id VARBINARY(36) NOT NULL,canonical_version VARCHAR(32) NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,canonical_bytes BLOB NOT NULL,
 state VARCHAR(16) NOT NULL,result_version INT NULL,result_bytes BLOB NULL,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_vc_command(command_namespace,actor_type,actor_id,authority_scope,request_id),
 CONSTRAINT chk_vc_command_state CHECK((state='RESERVED' AND result_version IS NULL AND result_bytes IS NULL) OR (state='SUCCEEDED' AND result_version=1 AND result_bytes IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE verification_credential_risk_attempt (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,store_id BIGINT NOT NULL,credential_id BIGINT NULL,command_id BIGINT NOT NULL,
 actor_type VARCHAR(32) NOT NULL,actor_id BIGINT NOT NULL,result_code VARCHAR(64) NOT NULL,counts_failure BOOLEAN NOT NULL,
 attempted_at DATETIME(3) NOT NULL,UNIQUE KEY uk_vc_attempt(command_id),KEY idx_vc_risk(order_id,counts_failure,attempted_at)
) ENGINE=InnoDB;
CREATE TABLE verification_credential_refresh (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,user_id BIGINT NOT NULL,command_id BIGINT NOT NULL,
 refresh_kind VARCHAR(16) NOT NULL,credential_id BIGINT NOT NULL,committed_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_vc_refresh(command_id),KEY idx_vc_refresh_window(order_id,user_id,committed_at),
 CONSTRAINT chk_vc_refresh_kind CHECK(refresh_kind IN('INITIAL','AUTO','MANUAL'))
) ENGINE=InnoDB;
