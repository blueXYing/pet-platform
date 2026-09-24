-- ADM-001 service write-side storage contract, MySQL 8.0.16+.
--
-- This is an isolated DDL contract review artifact (33号). It is deliberately not
-- a Flyway migration and must run only after the tables in SQL06 (service_item /
-- service_category) have been provisioned. The Flyway delta for already-provisioned
-- environments is backend/pet-boot/src/main/resources/db/service-migration/V27__service_write.sql.
-- All DATETIME(3) values are UTC. IDs are Snowflake BIGINT values in storage and are
-- serialized as decimal strings by HTTP contracts.
--
-- Approved by the 2026-09-22 human ruling (SVCW-D1/D3/D4): five-value status,
-- edit restricted to DRAFT/REJECTED/OFFLINE, append-only review decisions,
-- governance audit for FORCE_OFFLINE, cover/list-price/pet-type/staff columns,
-- submission_no + submitted_at for the 24h review SLA. No stock/sold-out fact and
-- no soft-delete column: those remain unresolved product questions and are absent
-- on purpose (proposal SVCW-D9).

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

-- Append-only review decisions: one row per APPROVE/REJECT on a submission.
-- Existing rows keep history; nothing here is ever updated in place.
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

-- Platform governance audit. FORCE_OFFLINE is not a review conclusion on a
-- submission, so it lives apart from service_review_decision (proposal SVCW-D3/D6).
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

-- ============================================================
-- v0.2 勘误/增补（2026-09-24，缺陷F3：宽松草稿可空化修复）
-- ============================================================
-- 缺陷来源：PR#78 窗口E2E取证 §8.3。上方 v0.1 原文保持不变；本节为可执行的增补 DDL，
-- 供全新环境在 v0.1 之后执行。已开通环境的等价 Flyway 增量为
-- backend/pet-boot/src/main/resources/db/service-migration/V28__service_draft_nullable.sql。
--
-- 依据：10号 §4.10.1（2026-09-22 已批）商家创建草稿"业务字段均可空存草稿"，
-- 提交审核时才必填齐全。但 service_item 基表（06号）的五个业务列
-- category_id/service_name/price/duration_minutes/fulfillment_type 为 NOT NULL 且无默认，
-- 最小草稿（如仅填名称甚至全空）必然 DataIntegrity → 503，与已批契约冲突。
-- 本节将五列可空化：仅 DRAFT/REJECTED/OFFLINE（可编辑态）行可能携带 NULL；
-- 提交审核门（应用层 validateSubmission）在进入 REVIEWING 前重校验全部必填集
-- （名称2-50、ENABLED类目、fulfillment、price>0、duration 1..10080、适用宠物类型、
-- 封面归属），REVIEWING/ACTIVE 行始终字段齐全，C端读侧（仅ACTIVE可见）不受影响。
-- 既有行无需回填；v0.1 的 CHECK（price>0、duration_minutes>0）按三值逻辑放行 NULL。
ALTER TABLE service_item
    MODIFY COLUMN category_id BIGINT NULL
        COMMENT '服务类目ID（草稿可空；提交审核时须命中ENABLED类目）',
    MODIFY COLUMN service_name VARCHAR(128) NULL
        COMMENT '服务名称（草稿可空；提交审核时2-50必填）',
    MODIFY COLUMN price DECIMAL(18,2) NULL
        COMMENT '售价（草稿可空；提交审核时>0必填）',
    MODIFY COLUMN duration_minutes INT NULL
        COMMENT '单次服务时长，用于分钟级排期（草稿可空；提交审核时1..10080必填）',
    MODIFY COLUMN fulfillment_type VARCHAR(32) NULL
        COMMENT 'IN_STORE/PICKUP_DELIVERY（草稿可空；提交审核时必填）';
