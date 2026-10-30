-- CCR-SVC-CAT-001 service category dictionary delta (contract 74 §2.1/§9).
-- Isolated opt-in migration only, following V27/V28: executes against an explicitly
-- named svcw001_* database holding the SQL06 service tables (pet.service.command.
-- migration-enabled + migration-database); the shared default datasource never runs it.
--
-- Ordered as approved by CCR-SVC-CAT-001 §3/§6: add nullable dictionary columns plus the
-- version optimistic-lock column (NOT NULL DEFAULT 0) -> backfill the 11 existing category
-- codes by category_name -> fail-closed tightening to NOT NULL + UNIQUE + parent index ->
-- append-only audit table. Existing rows are compatible without data loss and read
-- version = 0; service_item.category_id is untouched.
--
-- Executable on both an empty service_category table (backfill updates 0 rows) and an
-- existing dictionary holding the 11 standard rows. This file deliberately does NOT
-- INSERT the 11 dictionary rows: their Snowflake ids belong to the existing seed /
-- operations flow, and CCR-SVC-CAT-001 only authorizes a code backfill.
--
-- Production safety: MySQL DDL implicitly commits, so a failed tightening step leaves
-- the nullable columns and the backfill in place. Do not rerun this whole file in that
-- state (the ADD COLUMN statements would fail with duplicate column). If any row still
-- has a NULL category_code after the backfill (a category outside the 11 known names),
-- assign its code manually and then execute only the two tightening/audit statements,
-- as documented in docs/03-database/70-Service-Category-Dictionary-Schema-v0.1.sql.

-- A. Nullable dictionary columns plus the optimistic-lock version column. version is added
--    directly as NOT NULL DEFAULT 0, so empty tables and existing rows are both safe with
--    no backfill: every pre-existing row reads 0.
ALTER TABLE service_category
    ADD COLUMN category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '稳定分类编号，创建后不可改；^[A-Z][A-Z0-9_]{1,31}$（应用层校验）' AFTER id,
    ADD COLUMN parent_id BIGINT NULL
        COMMENT '父分类ID；V1仅预留，平铺展示，不建父子UI' AFTER category_code,
    ADD COLUMN version INT NOT NULL DEFAULT 0
        COMMENT '乐观锁版本；创建为0，改名/图标/描述/排序/启停均+1' AFTER status,
    ADD COLUMN icon_url VARCHAR(512) NULL
        COMMENT '分类图标HTTPS URL；运营通过既有后台素材上传后填写' AFTER sort_no,
    ADD COLUMN description VARCHAR(200) NULL
        COMMENT '分类描述，热门服务卡片文案；≤200码点' AFTER icon_url;

-- Backfill the 11 approved codes by the existing unique category_name.
UPDATE service_category SET category_code = 'PET_GROOMING'         WHERE category_name = '宠物美容';
UPDATE service_category SET category_code = 'DOG_WALKING'          WHERE category_name = '遛狗陪护';
UPDATE service_category SET category_code = 'PET_BOARDING'         WHERE category_name = '宠物寄养';
UPDATE service_category SET category_code = 'PET_TRAINING'         WHERE category_name = '宠物训练';
UPDATE service_category SET category_code = 'HOME_FEEDING'         WHERE category_name = '上门喂养';
UPDATE service_category SET category_code = 'VET_ASSISTANT'        WHERE category_name = '兽医助理';
UPDATE service_category SET category_code = 'SMALL_PET_BOARDING'   WHERE category_name = '小宠寄养';
UPDATE service_category SET category_code = 'EXOTIC_HOME_FEEDING'  WHERE category_name = '异宠上门喂养';
UPDATE service_category SET category_code = 'EXOTIC_HEALTH_CHECK'  WHERE category_name = '异宠健康检查';
UPDATE service_category SET category_code = 'PLANT_CARE'           WHERE category_name = '绿植养护';
UPDATE service_category SET category_code = 'PLANT_BOARDING'       WHERE category_name = '植物代养';

-- Tighten only after the backfill. If a pre-existing category outside the 11 rows is
-- still NULL, this statement fails closed (and the migration stops) instead of silently
-- persisting an unaddressable dictionary row; assign the code manually, then rerun just
-- the statements below. ADD UNIQUE also fails closed on accidental duplicate codes.
ALTER TABLE service_category
    MODIFY COLUMN category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '稳定分类编号，创建后不可改；^[A-Z][A-Z0-9_]{1,31}$（应用层校验）',
    ADD UNIQUE KEY uk_service_category_code (category_code),
    ADD KEY idx_service_category_parent (parent_id);

-- Append-only write audit (CCR-SVC-CAT-001 §3.2). detail_json stores field-name /
-- before-after summaries only, never credentials. UNIQUE request_id backs write
-- idempotency; no update/delete path exists for this table.
CREATE TABLE service_category_audit (
    id             BIGINT NOT NULL,
    category_id    BIGINT NOT NULL,
    category_code  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_id    BIGINT NOT NULL,
    request_id     VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detail_json    JSON NULL,
    created_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_category_audit_request (request_id),
    KEY idx_service_category_audit_row (category_id, created_at, id),
    CONSTRAINT chk_service_category_audit_action CHECK (
        action_type IN ('CREATE','UPDATE','ENABLE','DISABLE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务分类字典写动作 append-only 审计';
