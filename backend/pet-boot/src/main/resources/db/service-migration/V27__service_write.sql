-- ADM-001 service write-side delta (SQL33). Isolated opt-in migration only:
-- executes against an explicitly named svcw001_* database holding the SQL06
-- service tables, never the shared default datasource (mirrors the guarded
-- admin-auth V26 precedent; see ServiceWriteConfiguration).
-- Existing rows are compatible without backfill: status stays one of the
-- already-legal values, new columns are nullable or defaulted.
ALTER TABLE service_item
    MODIFY COLUMN status VARCHAR(32) NOT NULL
        COMMENT 'DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED（C端可见性仅ACTIVE；值域由应用层状态机保证）',
    ADD COLUMN list_price DECIMAL(18,2) NULL
        COMMENT '划线价，可空，须>=price' AFTER price,
    ADD COLUMN cover_asset_id BIGINT NULL
        COMMENT '封面图素材ID（31号SERVICE_COVER私有资产引用；提交审核时应用层必填）' AFTER fulfillment_type,
    ADD COLUMN applicable_pet_types VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '适用宠物类型，逗号分隔 DOG/CAT/EXOTIC/ALL（ALL与其他互斥，应用层校验）' AFTER cover_asset_id,
    ADD COLUMN staff_requirement VARCHAR(200) NULL
        COMMENT '服务人员要求 0-200 字' AFTER applicable_pet_types,
    ADD COLUMN verification_required TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否需要核销，V1 默认需要' AFTER staff_requirement,
    ADD COLUMN aftersale_note VARCHAR(500) NULL COMMENT '售后说明' AFTER verification_required,
    ADD COLUMN remark VARCHAR(500) NULL COMMENT '备注' AFTER aftersale_note,
    ADD COLUMN submission_no INT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '累计提交审核次数（REJECT重提计数与决定表对齐）' AFTER remark,
    ADD COLUMN owner_user_id BIGINT NOT NULL DEFAULT 0
        COMMENT '商家主账号（创建时准入门禁证实的会话用户；ServiceReviewedEvent.v1收件人，E对齐2026-09-22）' AFTER submission_no,
    ADD COLUMN submitted_at DATETIME(3) NULL
        COMMENT '最近一次提交审核时间（审核SLA 24h起算）' AFTER owner_user_id,
    ADD CONSTRAINT chk_service_item_list_price
        CHECK (list_price IS NULL OR list_price >= price),
    ADD CONSTRAINT chk_service_item_price CHECK (price > 0),
    ADD CONSTRAINT chk_service_item_duration CHECK (duration_minutes > 0),
    ADD CONSTRAINT chk_service_item_submission_no CHECK (submission_no >= 0),
    ADD CONSTRAINT chk_service_item_owner CHECK (owner_user_id >= 0);

CREATE TABLE service_review_decision (
    id                     BIGINT NOT NULL,
    service_id             BIGINT NOT NULL,
    submission_no          INT UNSIGNED NOT NULL,
    decision_type          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    opinion                VARCHAR(500) NULL,
    decided_by_operator_id BIGINT NOT NULL,
    decided_at             DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARBINARY(512) NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_svc_decision_request (request_id),
    UNIQUE KEY uk_svc_decision_service_no (service_id, submission_no),
    KEY idx_svc_decision_service_time (service_id, decided_at, id),
    KEY idx_svc_decision_operator (decided_by_operator_id, decided_at),
    CONSTRAINT chk_svc_decision_ids CHECK (id > 0 AND service_id > 0 AND submission_no > 0),
    CONSTRAINT chk_svc_decision_type CHECK (decision_type IN ('APPROVE', 'REJECT')),
    CONSTRAINT chk_svc_decision_opinion CHECK (
        (decision_type = 'APPROVE' AND (opinion IS NULL OR CHAR_LENGTH(opinion) <= 500))
        OR (decision_type = 'REJECT' AND opinion IS NOT NULL
            AND CHAR_LENGTH(opinion) BETWEEN 10 AND 500)),
    CONSTRAINT chk_svc_decision_request CHECK (OCTET_LENGTH(request_id) BETWEEN 1 AND 512),
    CONSTRAINT chk_svc_decision_trace CHECK (trace_id IS NULL OR OCTET_LENGTH(trace_id) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务审核决定（append-only；REJECT意见10-500必填）';

CREATE TABLE service_governance_action (
    id                     BIGINT NOT NULL,
    service_id             BIGINT NOT NULL,
    action_type            VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    acted_by_operator_id   BIGINT NOT NULL,
    acted_at               DATETIME(3) NOT NULL,
    authz_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_version          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id             VARBINARY(512) NOT NULL,
    trace_id               VARCHAR(128) NULL,
    created_at             DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_svc_governance_request (request_id),
    KEY idx_svc_governance_service_time (service_id, acted_at, id),
    CONSTRAINT chk_svc_governance_type CHECK (action_type = 'FORCE_OFFLINE'),
    CONSTRAINT chk_svc_governance_reason CHECK (CHAR_LENGTH(reason) BETWEEN 10 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务治理动作审计（V1仅FORCE_OFFLINE）';
