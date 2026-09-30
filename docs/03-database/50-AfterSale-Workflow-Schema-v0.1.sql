-- Approved A1-A3. Manual migration after SQL49; never executed on application startup.

-- AFS-owned SQL50 fragment. Run by migration owner only. No application startup DDL.
-- Existing SQL48 cases/proofs remain readable; unknown historical cases cannot enter new workflow commands.
ALTER TABLE aftersale_case
 ADD workflow_revision INT NULL,
 ADD creator_command_id BIGINT NULL,
 ADD created_event_id BIGINT NULL,
 ADD city_code VARCHAR(64) NULL,
 ADD scope_version VARCHAR(128) NULL,
 ADD eligibility_anchor DATETIME(3) NULL,
 ADD eligibility_deadline DATETIME(3) NULL,
 ADD origin_cipher MEDIUMBLOB NULL,
 ADD content_cipher MEDIUMBLOB NULL,
 ADD type_code VARCHAR(64) NULL,
 ADD demand_code VARCHAR(64) NULL,
 ADD final_set_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD decision_id BIGINT NULL,
 ADD refund_order_id BIGINT NULL,
 ADD current_supplement_id BIGINT NULL,
 ADD duplicate_final_case_id BIGINT NULL,
 ADD UNIQUE KEY uk_aftersale_creator_command(creator_command_id),
 ADD UNIQUE KEY uk_aftersale_created_event(created_event_id),
 ADD UNIQUE KEY uk_aftersale_decision(decision_id),
 ADD UNIQUE KEY uk_aftersale_refund(refund_order_id),
 ADD CONSTRAINT chk_aftersale_workflow CHECK (workflow_revision IS NULL OR
  (workflow_revision=1 AND creator_command_id IS NOT NULL AND created_event_id IS NOT NULL
   AND scope_version IS NOT NULL AND eligibility_anchor IS NOT NULL AND eligibility_deadline IS NOT NULL
   AND eligibility_deadline=eligibility_anchor+INTERVAL 7 DAY AND origin_cipher IS NOT NULL
   AND content_cipher IS NOT NULL AND description='' AND decision_reason IS NULL AND type_code IS NOT NULL
   AND demand_code IS NOT NULL AND final_set_version IS NOT NULL
   AND ((active_flag=1 AND status IN ('PENDING','PROCESSING','WAITING_SUPPLEMENT'))
     OR (active_flag=0 AND status IN ('RESOLVED','WITHDRAWN','INVALIDATED','CLOSED')))));

CREATE TABLE aftersale_command (
 id BIGINT PRIMARY KEY,command_namespace VARBINARY(64) NOT NULL,actor_type VARBINARY(32) NOT NULL,
 actor_id BIGINT NOT NULL,scope VARBINARY(128) NOT NULL,request_id VARBINARY(512) NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 canonical_version VARCHAR(32) NOT NULL,canonical_bytes MEDIUMBLOB NOT NULL,
 state VARCHAR(16) NOT NULL,result_version INT NULL,result_bytes MEDIUMBLOB NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_aftersale_command_binding(command_namespace,actor_type,actor_id,scope,request_id),
 CHECK (canonical_version='canonical-v1'),
 CHECK ((state='RESERVED' AND result_version IS NULL AND result_bytes IS NULL)
  OR (state='SUCCEEDED' AND result_version=1 AND result_bytes IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE aftersale_transition (
 command_id BIGINT PRIMARY KEY,aftersale_id BIGINT NOT NULL,order_id BIGINT NOT NULL,
 from_status VARCHAR(32) NULL,to_status VARCHAR(32) NOT NULL,case_version BIGINT NOT NULL,
 action VARCHAR(64) NOT NULL,event_id BIGINT NOT NULL,actor_type VARCHAR(32) NOT NULL,actor_id BIGINT NULL,
 occurred_at DATETIME(3) NOT NULL,evidence_batch_id BIGINT NULL,supplement_id BIGINT NULL,
 decision_id BIGINT NULL,refund_order_id BIGINT NULL,detail_cipher MEDIUMBLOB NULL,
 UNIQUE KEY uk_aftersale_transition_version(aftersale_id,case_version),
 UNIQUE KEY uk_aftersale_transition_event(event_id),KEY idx_aftersale_transition_case(aftersale_id,occurred_at),
 CHECK(case_version>=0)
) ENGINE=InnoDB;

CREATE TABLE aftersale_evidence_batch (
 id BIGINT PRIMARY KEY,aftersale_id BIGINT NOT NULL,command_id BIGINT NOT NULL,
 submitter_type VARCHAR(16) NOT NULL,submitter_id BIGINT NOT NULL,supplement_id BIGINT NULL,
 content_cipher MEDIUMBLOB NOT NULL,content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 moderation_version VARCHAR(128) NOT NULL,created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_aftersale_batch_command(command_id),KEY idx_aftersale_batch_case(aftersale_id,id),
 CHECK(submitter_type IN ('USER','MERCHANT'))
) ENGINE=InnoDB;
CREATE TABLE aftersale_evidence_asset (
 batch_id BIGINT NOT NULL,asset_id BIGINT NOT NULL,owner_user_id BIGINT NOT NULL,
 object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 object_version_ref VARCHAR(256) NOT NULL,asset_fact_version VARCHAR(128) NOT NULL,
 media_type VARCHAR(100) NOT NULL,asset_bytes BIGINT NOT NULL,
 PRIMARY KEY(batch_id,asset_id),CHECK(asset_bytes>0)
) ENGINE=InnoDB;

CREATE TABLE aftersale_supplement (
 id BIGINT PRIMARY KEY,aftersale_id BIGINT NOT NULL,request_command_id BIGINT NOT NULL,
 target_party VARCHAR(16) NOT NULL,reason_cipher MEDIUMBLOB NOT NULL,deadline DATETIME(3) NOT NULL,
 status VARCHAR(16) NOT NULL,created_at DATETIME(3) NOT NULL,closed_at DATETIME(3) NULL,
 completion_command_id BIGINT NULL,completion_verification_id BIGINT NULL,
 active_case_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN aftersale_id ELSE NULL END) STORED,
 UNIQUE KEY uk_aftersale_supplement_command(request_command_id),UNIQUE KEY uk_aftersale_supplement_active(active_case_id),
 KEY idx_aftersale_supplement_due(status,deadline,id),
 CHECK(target_party IN ('USER','MERCHANT')),
 CHECK(deadline>created_at),
 CHECK((status='OPEN' AND closed_at IS NULL AND completion_command_id IS NULL AND completion_verification_id IS NULL)
  OR (status IN ('SUBMITTED','TIMED_OUT','CANCELED') AND closed_at IS NOT NULL AND completion_command_id IS NOT NULL AND completion_verification_id IS NULL)
  OR (status='CANCELED' AND closed_at IS NOT NULL AND completion_command_id IS NULL AND completion_verification_id IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE aftersale_decision (
 id BIGINT PRIMARY KEY,aftersale_id BIGINT NOT NULL,order_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 command_id BIGINT NOT NULL,event_id BIGINT NOT NULL,actor_id BIGINT NOT NULL,
 decision_type VARCHAR(32) NOT NULL,refund_amount DECIMAL(18,2) NULL,paid_amount DECIMAL(18,2) NOT NULL,
 source_case_version BIGINT NOT NULL,reason_cipher MEDIUMBLOB NOT NULL,
 authz_version VARCHAR(128) NOT NULL,scope_version VARCHAR(128) NOT NULL,
 proof_cipher MEDIUMBLOB NOT NULL,order_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 decided_at DATETIME(3) NOT NULL,refund_order_id BIGINT NULL,refund_no BIGINT NULL,
 refund_created_event_id BIGINT NULL,refund_created_at DATETIME(3) NULL,
 UNIQUE KEY uk_aftersale_decision_case(aftersale_id),UNIQUE KEY uk_aftersale_decision_command(command_id),
 UNIQUE KEY uk_aftersale_decision_event(event_id),UNIQUE KEY uk_aftersale_decision_refund(refund_order_id),
 CHECK(paid_amount>0 AND source_case_version>=0),
 CHECK((decision_type='FULL_REFUND' AND refund_amount IS NOT NULL AND refund_amount=paid_amount AND order_token_hash IS NOT NULL)
  OR (decision_type='PARTIAL_REFUND' AND refund_amount IS NOT NULL AND refund_amount>0 AND refund_amount<paid_amount AND order_token_hash IS NOT NULL)
  OR (decision_type IN ('REJECT','RESERVICE','OTHER') AND refund_amount IS NULL AND order_token_hash IS NULL AND refund_order_id IS NULL)),
 CHECK((refund_order_id IS NULL AND refund_no IS NULL AND refund_created_event_id IS NULL AND refund_created_at IS NULL)
  OR (refund_order_id IS NOT NULL AND refund_no IS NOT NULL AND refund_created_event_id IS NOT NULL AND refund_created_at IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE aftersale_recovery_issue (
 supplement_id BIGINT NOT NULL,issue_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aftersale_id BIGINT NOT NULL,store_id BIGINT NULL COMMENT 'Unknown only when the source case is missing',status VARCHAR(16) NOT NULL,
 occurrences BIGINT NOT NULL,created_at DATETIME(3) NOT NULL,last_seen_at DATETIME(3) NOT NULL,
 resolved_at DATETIME(3) NULL,PRIMARY KEY(supplement_id,issue_code),
 CHECK(issue_code IN ('AFTERSALE_PROOF_INVALID','AFTERSALE_TASK_CONFLICT')),
 CHECK(occurrences>0),CHECK((status='OPEN' AND resolved_at IS NULL) OR (status='RESOLVED' AND resolved_at IS NOT NULL))
) ENGINE=InnoDB;


-- SQL50 integration fragment. Apply after SQL49. Historical SQL42/49 source shapes stay valid.
-- Internal API23 admits exact request keys up to 512 UTF-8 bytes; AFS audit shares this ORDER table.
ALTER TABLE order_status_log MODIFY request_id VARBINARY(512) NULL;
ALTER TABLE refund_execution DROP CHECK chk_refund_source, DROP CHECK chk_late_refund_amount,
 ADD CONSTRAINT chk_refund_source CHECK (
  (source_biz_id IS NULL AND source_decision_id IS NULL AND
   ((source_type IS NULL AND source_event_id IS NULL AND late_event_id IS NOT NULL)
    OR (source_type IS NOT NULL AND source_event_id IS NOT NULL AND source_event_id>0 AND
     ((source_type='LATE_PAYMENT_TIMEOUT' AND late_event_id IS NOT NULL AND late_event_id=source_event_id)
      OR (source_type='MERCHANT_REJECT_ORDER' AND late_event_id IS NULL)))))
  OR (source_type IS NOT NULL AND source_type IN ('MERCHANT_APPROVED','MERCHANT_TIMEOUT_AUTO','AFTERSALE_DECISION')
   AND source_biz_id IS NOT NULL AND source_biz_id>0 AND source_decision_id IS NOT NULL AND source_decision_id>0
   AND late_event_id IS NULL AND source_event_id IS NULL)),
 ADD CONSTRAINT chk_refund_authorized_amount CHECK (channel_paid_amount>0 AND refund_amount>0
  AND ((source_type IS NOT NULL AND source_type='AFTERSALE_DECISION' AND refund_amount<=channel_paid_amount)
   OR ((source_type IS NULL OR source_type<>'AFTERSALE_DECISION') AND refund_amount=channel_paid_amount)));

-- refund_order.refund_ratio remains DECIMAL(10,6), SQL06:426; no change in precision.
ALTER TABLE refund_order ADD CONSTRAINT chk_refund_source_type_amount CHECK (
 refund_amount>0 AND refund_ratio>=0 AND refund_ratio<=1 AND
 ((source_type='AFTERSALE_DECISION' AND (refund_type='PARTIAL' OR (refund_type='FULL' AND refund_ratio=1)) AND aftersale_id IS NOT NULL AND refund_application_id IS NULL)
 OR (source_type<>'AFTERSALE_DECISION' AND refund_type='FULL' AND refund_ratio=1)));
CREATE TABLE order_aftersale_source_proof (
 case_id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 case_version BIGINT NOT NULL,order_version BIGINT NOT NULL,status VARCHAR(32) NOT NULL,
 source_stage VARCHAR(32) NOT NULL,source_json LONGTEXT COLLATE utf8mb4_bin NOT NULL,created_at DATETIME(3) NOT NULL,
 KEY idx_order_aftersale_source(order_id,case_id),CHECK(case_version>=0 AND order_version>=0),
 CHECK(source_stage IN ('VERIFIED','UNVERIFIED_POST_START')),CHECK(JSON_VALID(source_json))
) ENGINE=InnoDB;
CREATE TABLE order_aftersale_refund_commit (
 order_id BIGINT PRIMARY KEY,store_id BIGINT NOT NULL,case_id BIGINT NOT NULL,decision_id BIGINT NOT NULL,
 refund_order_id BIGINT NOT NULL,order_version BIGINT NOT NULL,refund_type VARCHAR(16) NOT NULL,
 refund_amount DECIMAL(18,2) NOT NULL,source_stage VARCHAR(32) NOT NULL,
 funding_evidence_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,token VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 created_at DATETIME(3) NOT NULL,success_event_id BIGINT NULL,succeeded_at DATETIME(3) NULL,
 UNIQUE KEY uk_order_afs_case(case_id),UNIQUE KEY uk_order_afs_decision(decision_id),
 UNIQUE KEY uk_order_afs_refund(refund_order_id),UNIQUE KEY uk_order_afs_token(token),
 UNIQUE KEY uk_order_afs_success(success_event_id),CHECK(refund_type IN ('FULL','PARTIAL')),
 CHECK(refund_amount>0),CHECK(source_stage IN ('VERIFIED','UNVERIFIED_POST_START')),
 CHECK((success_event_id IS NULL AND succeeded_at IS NULL) OR (success_event_id IS NOT NULL AND succeeded_at IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE refund_aftersale_proof (
 refund_order_id BIGINT PRIMARY KEY,case_id BIGINT NOT NULL,decision_id BIGINT NOT NULL,command_id BIGINT NOT NULL,
 funding_evidence_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,proof_json JSON NOT NULL,created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_refund_afs_case(case_id),UNIQUE KEY uk_refund_afs_decision(decision_id),UNIQUE KEY uk_refund_afs_command(command_id)
) ENGINE=InnoDB;
CREATE TABLE payment_refund_funding_proof (
 refund_order_id BIGINT PRIMARY KEY,committed_evidence_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
 check_json JSON NOT NULL,evidence_json JSON NOT NULL,request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;


-- THIRD_PARTY Contract50: independent typed AFS grants, no merchant application/revision alias.
CREATE TABLE aftersale_asset_read_grant (
 id BIGINT PRIMARY KEY,after_sale_id BIGINT NOT NULL,batch_id BIGINT NOT NULL,asset_id BIGINT NOT NULL,
 actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,actor_id BIGINT NOT NULL,
 request_id VARBINARY(512) NOT NULL,key_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_digest BINARY(32) NOT NULL,request_hash BINARY(32) NOT NULL,proof_hash BINARY(32) NOT NULL,
 reason_cipher VARBINARY(4096) NOT NULL,status VARCHAR(16) NOT NULL,issued_at DATETIME(3) NOT NULL,
 expires_at DATETIME(3) NOT NULL,consumed_at DATETIME(3) NULL,
 UNIQUE KEY uk_afs_asset_read_request(actor_type,actor_id,after_sale_id,request_id),
 UNIQUE KEY uk_afs_asset_read_token(token_digest),KEY idx_afs_asset_read_case(after_sale_id,batch_id),
 CHECK (id>0 AND after_sale_id>0 AND batch_id>0 AND asset_id>0 AND actor_id>0),
 CHECK (actor_type IN ('USER','PLATFORM_OPERATOR')),
 CHECK (status IN ('ISSUED','CONSUMED','REVOKED','EXPIRED')),
 CHECK (expires_at=TIMESTAMPADD(MINUTE,5,issued_at)),
 CHECK ((status='CONSUMED' AND consumed_at IS NOT NULL) OR (status<>'CONSUMED' AND consumed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE aftersale_asset_access_audit (
 id BIGINT PRIMARY KEY,grant_id BIGINT NOT NULL,action VARCHAR(16) NOT NULL,result VARCHAR(16) NOT NULL,
 actor_type VARCHAR(32) NOT NULL,actor_id BIGINT NOT NULL,request_id VARBINARY(512) NOT NULL,created_at DATETIME(3) NOT NULL,
 KEY idx_afs_asset_audit_grant(grant_id,created_at),
 CHECK(action IN ('ISSUE','CONSUME')),CHECK(result IN ('SUCCESS','REPLAY','STARTED','DENIED','FAILED'))
) ENGINE=InnoDB;
