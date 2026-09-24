-- F3 loose-draft nullability fix (10号 §4.10.1 已批草稿宽松；PR#78 窗口E2E取证 §8.3).
-- The SQL06 base columns of service_item were born NOT NULL without defaults, so a minimal
-- draft (any business field omitted) could only fail: the writer NPE'd on primitive
-- unboxing or MySQL rejected the row (DataIntegrity) — both surfaced as 503 instead of the
-- approved 201 DRAFT. This delta makes the five business columns truly nullable; the
-- submission gate (ServiceCommandService.validateSubmission) still re-validates the full
-- required set before DRAFT/REJECTED/OFFLINE may enter REVIEWING, so only editable rows
-- may ever carry NULLs and REVIEWING/ACTIVE rows stay complete. No data backfill needed:
-- existing rows keep their values, and the SQL33 CHECKs (price > 0, duration_minutes > 0)
-- pass NULL by SQL three-valued logic. Fresh environments get the same shape from the
-- SQL33 v0.2 勘误/增补 section; this Flyway file is the delta for already-provisioned ones.
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
