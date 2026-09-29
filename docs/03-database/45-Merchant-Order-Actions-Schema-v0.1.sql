-- Approved D1/D2/D3. Apply only in isolated tests here; production rollout is separate.
CREATE TABLE order_merchant_command (
 id BIGINT NOT NULL PRIMARY KEY,
 command_namespace VARBINARY(64) NOT NULL,
 actor_type VARBINARY(32) NOT NULL,
 actor_id BIGINT NOT NULL,
 authority_scope VARBINARY(64) NOT NULL,
 request_id VARBINARY(36) NOT NULL,
 canonical_version VARCHAR(32) NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 canonical_bytes BLOB NOT NULL,
 state VARCHAR(16) NOT NULL,
 result_version INT NULL,
 result_bytes BLOB NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_order_merchant_command(command_namespace,actor_type,actor_id,authority_scope,request_id),
 CHECK (state IN ('RESERVED','SUCCEEDED')),
 CHECK ((state='RESERVED' AND result_version IS NULL AND result_bytes IS NULL)
   OR (state='SUCCEEDED' AND result_version=1 AND result_bytes IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_merchant_decision (
 id BIGINT NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 confirm_round INT NOT NULL,
 command_id BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL,
 operator_id BIGINT NOT NULL,
 decided_at DATETIME(3) NOT NULL,
 event_id BIGINT NOT NULL,
 refund_order_id BIGINT NULL,
 reason_code VARCHAR(32) NULL,
 reason_text VARBINARY(2048) NULL COMMENT 'Protected UTF-8 text; no plaintext logging',
 internal_note VARBINARY(2048) NULL COMMENT 'Protected store-only text',
 UNIQUE KEY uk_order_merchant_round(order_id,confirm_round),
 UNIQUE KEY uk_order_merchant_command_id(command_id),
 UNIQUE KEY uk_order_merchant_event(event_id),
 CHECK(confirm_round=0),
 CHECK((action='CONFIRM' AND refund_order_id IS NULL AND reason_code IS NULL AND reason_text IS NULL)
   OR (action='REJECT' AND refund_order_id IS NOT NULL AND reason_code IS NOT NULL
      AND reason_text IS NOT NULL AND internal_note IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Compatibility expansion: deploy source-aware readers/writers with writes disabled,
-- then backfill historical rows before enabling the new source. No automatic startup migration.
ALTER TABLE refund_execution
 MODIFY late_event_id BIGINT NULL,
 ADD source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD source_event_id BIGINT NULL;
UPDATE refund_execution SET source_type='LATE_PAYMENT_TIMEOUT',source_event_id=late_event_id
 WHERE source_type IS NULL AND source_event_id IS NULL AND late_event_id IS NOT NULL;
-- Nullable source fields support old late writers during compatibility rollout only.
-- Application validation accepts both NULL only for proven legacy late rows.
ALTER TABLE refund_execution ADD CONSTRAINT chk_refund_source CHECK (
 (source_type IS NULL AND source_event_id IS NULL AND late_event_id IS NOT NULL)
 OR (source_type IS NOT NULL AND source_event_id IS NOT NULL AND source_event_id>0 AND
   ((source_type='LATE_PAYMENT_TIMEOUT' AND late_event_id IS NOT NULL AND late_event_id=source_event_id)
    OR (source_type='MERCHANT_REJECT_ORDER' AND late_event_id IS NULL))));
