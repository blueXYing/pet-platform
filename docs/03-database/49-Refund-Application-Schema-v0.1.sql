-- Approved R1/R2. Run manually in isolated QA only; never startup/production DDL.
-- Legacy application rows have no verifiable command/identity/payment proof. Refuse, do not invent history.
CREATE TABLE refund_application_migration_gate (legacy_rows BIGINT NOT NULL CHECK (legacy_rows=0));
INSERT INTO refund_application_migration_gate SELECT COUNT(*) FROM refund_application;
DROP TABLE refund_application_migration_gate;

ALTER TABLE refund_application
 DROP INDEX uk_refund_application_request,
 MODIFY merchant_deadline DATETIME(3) NOT NULL,
 ADD store_id BIGINT NOT NULL, ADD merchant_id BIGINT NOT NULL, ADD reservation_id BIGINT NOT NULL,
 ADD payment_id BIGINT NOT NULL, ADD payment_no BIGINT NOT NULL, ADD payment_success_event_id BIGINT NOT NULL,
 ADD channel_trade_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ADD paid_at DATETIME(3) NOT NULL, ADD created_command_id BIGINT NOT NULL, ADD created_event_id BIGINT NOT NULL,
 ADD version BIGINT NOT NULL DEFAULT 0, ADD decision_id BIGINT NULL, ADD refund_order_id BIGINT NULL,
 ADD reason_text_cipher VARBINARY(4096) NULL,
 ADD active_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING_MERCHANT','APPROVED','AUTO_APPROVED') THEN order_id ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_refund_application_active(active_order_id),
 ADD UNIQUE KEY uk_refund_application_command(created_command_id),
 ADD UNIQUE KEY uk_refund_application_event(created_event_id),
 ADD UNIQUE KEY uk_refund_application_decision(decision_id),
 ADD UNIQUE KEY uk_refund_application_refund(refund_order_id),
 ADD CONSTRAINT chk_refund_application_state CHECK (
  (status='PENDING_MERCHANT' AND version=0 AND decision_id IS NULL AND decided_at IS NULL AND refund_order_id IS NULL)
  OR (status IN ('APPROVED','AUTO_APPROVED','REJECTED') AND version=1 AND decision_id IS NOT NULL AND decided_at IS NOT NULL)),
 ADD CONSTRAINT chk_refund_application_plaintext CHECK (reason_text IS NULL AND merchant_decision_reason IS NULL),
 ADD CONSTRAINT chk_refund_application_amount CHECK (requested_amount>0),
 ADD CONSTRAINT chk_refund_application_deadline CHECK (merchant_deadline=created_at+INTERVAL 24 HOUR);

CREATE TABLE refund_application_command (
 id BIGINT PRIMARY KEY,command_namespace VARBINARY(64) NOT NULL,actor_type VARBINARY(32) NOT NULL,
 actor_id BIGINT NOT NULL,scope VARBINARY(128) NOT NULL,request_id VARBINARY(128) NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 canonical_version VARCHAR(32) NOT NULL,canonical_bytes MEDIUMBLOB NOT NULL,
 state VARCHAR(16) NOT NULL,result_version INT NULL,result_bytes MEDIUMBLOB NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_refund_application_binding(command_namespace,actor_type,actor_id,scope,request_id),
 CHECK (canonical_version='canonical-v1'),
 CHECK ((state='RESERVED' AND result_version IS NULL AND result_bytes IS NULL)
  OR (state='SUCCEEDED' AND result_version=1 AND result_bytes IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refund_application_decision (
 id BIGINT PRIMARY KEY,application_id BIGINT NOT NULL,command_id BIGINT NOT NULL,event_id BIGINT NOT NULL,
 status VARCHAR(32) NOT NULL,operator_type VARCHAR(32) NOT NULL,operator_id BIGINT NULL,
 request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason_cipher VARBINARY(4096) NULL,decided_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_refund_decision_application(application_id),UNIQUE KEY uk_refund_decision_command(command_id),
 UNIQUE KEY uk_refund_decision_event(event_id),
 CHECK ((status IN ('APPROVED','REJECTED') AND operator_type='USER' AND operator_id IS NOT NULL)
  OR (status='AUTO_APPROVED' AND operator_type='SYSTEM' AND operator_id IS NULL)),
 CHECK ((status='REJECTED' AND reason_cipher IS NOT NULL) OR (status IN ('APPROVED','AUTO_APPROVED') AND reason_cipher IS NULL))
) ENGINE=InnoDB;

ALTER TABLE pet_order ADD refund_application_status VARCHAR(32) NULL;
CREATE TABLE order_refund_application_proof (
 application_id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,store_id BIGINT NOT NULL,merchant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,reservation_id BIGINT NOT NULL,payment_id BIGINT NOT NULL,payment_success_event_id BIGINT NOT NULL,
 channel_trade_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,paid_amount DECIMAL(18,2) NOT NULL,
 paid_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL,merchant_deadline DATETIME(3) NOT NULL,
 applied_order_version BIGINT NOT NULL,application_version BIGINT NOT NULL,application_status VARCHAR(32) NOT NULL,
 decision_id BIGINT NULL,decided_at DATETIME(3) NULL,KEY idx_order_refund_application(order_id,created_at),
 UNIQUE KEY uk_order_refund_application_decision(decision_id)
) ENGINE=InnoDB;
CREATE TABLE order_refund_application_commit (
 order_id BIGINT PRIMARY KEY,application_id BIGINT NOT NULL,decision_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 refund_order_id BIGINT NOT NULL,order_version BIGINT NOT NULL,created_at DATETIME(3) NOT NULL,
 success_event_id BIGINT NULL,succeeded_at DATETIME(3) NULL,
 UNIQUE KEY uk_order_application_commit(application_id),UNIQUE KEY uk_order_application_decision(decision_id),
 UNIQUE KEY uk_order_application_refund(refund_order_id),UNIQUE KEY uk_order_application_success(success_event_id)
) ENGINE=InnoDB;

-- Keep old source semantics, including legacy late rows with NULL source_type/source_event_id.
ALTER TABLE refund_execution DROP CHECK chk_refund_source,
 ADD source_biz_id BIGINT NULL, ADD source_decision_id BIGINT NULL,
 ADD CONSTRAINT chk_refund_source CHECK (
  (source_biz_id IS NULL AND source_decision_id IS NULL AND
   ((source_type IS NULL AND source_event_id IS NULL AND late_event_id IS NOT NULL)
    OR (source_type IS NOT NULL AND source_event_id IS NOT NULL AND source_event_id>0 AND
     ((source_type='LATE_PAYMENT_TIMEOUT' AND late_event_id IS NOT NULL AND late_event_id=source_event_id)
      OR (source_type='MERCHANT_REJECT_ORDER' AND late_event_id IS NULL)))))
  OR (source_type IN ('MERCHANT_APPROVED','MERCHANT_TIMEOUT_AUTO') AND source_type IS NOT NULL
   AND source_biz_id IS NOT NULL AND source_biz_id>0 AND source_decision_id IS NOT NULL AND source_decision_id>0
   AND late_event_id IS NULL AND source_event_id IS NULL));

-- Owner recovery isolates malformed application/task facts without persisting exception text or PII.
CREATE TABLE refund_application_reconciliation_issue (
 application_id BIGINT NOT NULL,issue_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id BIGINT NOT NULL,store_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,
 occurrence_count BIGINT NOT NULL,created_at DATETIME(3) NOT NULL,last_seen_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,resolved_at DATETIME(3) NULL,
 PRIMARY KEY(application_id,issue_code),KEY idx_refund_application_issue_status(status,application_id),
 CHECK (issue_code IN ('APPLICATION_PROOF_INVALID','APPLICATION_TASK_CONFLICT')),
 CHECK (occurrence_count>0),
 CHECK ((status='OPEN' AND resolved_at IS NULL) OR (status='RESOLVED' AND resolved_at IS NOT NULL))
) ENGINE=InnoDB;
