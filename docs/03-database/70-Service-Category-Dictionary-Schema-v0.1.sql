-- 70-服务分类字典 Schema v0.1
-- 状态：实现输入（2026-10-30）；依据 CCR-SVC-CAT-001（APPROVED）§3/§6/§8 与
--       74-服务目录页契约 v0.1 §2.1/§9。
-- 性质：DDL 契约/验收文档，不是默认 Flyway 迁移。已开通环境的等价 Flyway 增量为
--       backend/pet-boot/src/main/resources/db/service-migration/V29__service_category_dictionary.sql。
-- 目标：MySQL 8.0.16+（CHECK 约束需 8.0.16+）/ InnoDB / utf8mb4_0900_ai_ci；
--       DATETIME(3) 一律 UTC；ID 为 Snowflake BIGINT（HTTP/JSON 使用十进制 String）。
--
-- 范围：
--   1. service_category 增加 category_code / parent_id / icon_url / description，
--      以及运营端乐观锁 version（INT NOT NULL DEFAULT 0）；
--   2. 按既有 category_name 回填 11 个稳定 category_code；
--   3. 回填后收紧 category_code NOT NULL + 全局唯一 + parent_id 索引；
--   4. 新增 append-only service_category_audit 审计表。
-- 不做：不改 service_item.category_id；不迁移商家服务归属；不建父子层级 UI/外键；
--       不改订单/支付/退款/核销/售后状态机；不 INSERT 新分类行。
--
-- 迁移顺序（必须按 A→E 执行；MySQL DDL 隐式提交，没有事务级回滚）：
--   A. 先加可空字典列（category_code/parent_id/icon_url/description）与 version
--      乐观锁列（直接 NOT NULL DEFAULT 0，存量行自动为 0，无需回填）；
--   B. 按 category_name 回填 11 个 code；
--   C. 执行“回填后核对”查询，确认无 NULL、无重复、11 个标准分类 code 正确；
--   D. 收紧 category_code NOT NULL + UNIQUE + parent 索引；
--   E. 建 service_category_audit。
--
-- 适用库：
--   * 存量库：service_category 已存在且通常含 11 个标准分类 → 全量顺序执行即可；
--   * 空表：A/B 正常执行（B 更新 0 行），D/E 正常执行；本文件不播种分类数据，
--     初始字典行由既有部署种子/运营创建流程提供（Snowflake id 由应用生成）。
--
-- ============================================================
-- A. 加可空字典列与 version 乐观锁列
-- ============================================================
-- version 直接以 NOT NULL DEFAULT 0 加入：空表与存量库都安全，存量行自动读 0，
-- 无需额外回填；后续运营端写动作按 expectedVersion 做 CAS 并 version+1。
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

-- ============================================================
-- B. 回填 11 个既有分类编号（按既有唯一 category_name 匹配）
-- ============================================================
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

-- ============================================================
-- C. 回填后核对（收紧前必须全部通过；只读）
-- ============================================================
-- C1. 期望为 0 行：仍有未回填的存量分类（可能是 11 类之外的自定义分类）。
--     CCR-SVC-CAT-001 §6/§9：必须先人工补 code，不能带 NULL 收紧。
SELECT id, category_name
FROM service_category
WHERE category_code IS NULL;

-- C2. 期望为 0 行：重复 code（正常回填不会产生；若有人工写入需先修正）。
SELECT category_code, COUNT(*) AS duplicated
FROM service_category
WHERE category_code IS NOT NULL
GROUP BY category_code
HAVING COUNT(*) > 1;

-- C3. 期望为 0 行：11 个标准分类的 code 与 74 号契约 §2.1 不一致或缺失。
SELECT expected.category_name,
       expected.category_code AS expected_code,
       actual.category_code   AS actual_code
FROM (
    SELECT '宠物美容' AS category_name, 'PET_GROOMING' AS category_code
    UNION ALL SELECT '遛狗陪护', 'DOG_WALKING'
    UNION ALL SELECT '宠物寄养', 'PET_BOARDING'
    UNION ALL SELECT '宠物训练', 'PET_TRAINING'
    UNION ALL SELECT '上门喂养', 'HOME_FEEDING'
    UNION ALL SELECT '兽医助理', 'VET_ASSISTANT'
    UNION ALL SELECT '小宠寄养', 'SMALL_PET_BOARDING'
    UNION ALL SELECT '异宠上门喂养', 'EXOTIC_HOME_FEEDING'
    UNION ALL SELECT '异宠健康检查', 'EXOTIC_HEALTH_CHECK'
    UNION ALL SELECT '绿植养护', 'PLANT_CARE'
    UNION ALL SELECT '植物代养', 'PLANT_BOARDING'
) AS expected
LEFT JOIN service_category AS actual
       ON actual.category_name = expected.category_name
WHERE actual.category_code IS NULL
   OR actual.category_code <> expected.category_code;

-- ============================================================
-- D. 收紧：NOT NULL + 全局唯一 + parent 索引
-- ============================================================
-- 若 C1 非空，本语句会 fail-closed（strict 模式下 NOT NULL 转换失败），迁移停止；
-- 不要忽略或强行改列，先补 code 再执行 D。ADD UNIQUE 同样在重复 code 时失败。
ALTER TABLE service_category
    MODIFY COLUMN category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '稳定分类编号，创建后不可改；^[A-Z][A-Z0-9_]{1,31}$（应用层校验）',
    ADD UNIQUE KEY uk_service_category_code (category_code),
    ADD KEY idx_service_category_parent (parent_id);

-- ============================================================
-- E. append-only 审计表（CCR-SVC-CAT-001 §3.2）
-- ============================================================
-- detail_json 只记录字段名/前后值摘要，不记录凭据；request_id 唯一支撑写幂等；
-- 本表只有 INSERT 路径，不提供 UPDATE/DELETE。审计保留期沿用平台审计基线。
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

-- ============================================================
-- F. 完成后验证（只读）
-- ============================================================
-- F1. 列定义、NOT NULL（含 version DEFAULT 0）、唯一键与 parent 索引。
SHOW CREATE TABLE service_category;

-- F2. 唯一键/索引存在性（期望各返回 1 行）。
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'service_category'
  AND index_name IN ('uk_service_category_code', 'idx_service_category_parent')
ORDER BY index_name, seq_in_index;

-- F3. 审计表结构（含 CHECK）。
SHOW CREATE TABLE service_category_audit;

-- F4. 标准分类排序结果（C 端排序第一键 sort_no，第二键 id）。
SELECT category_code, category_name, sort_no, status
FROM service_category
ORDER BY sort_no, id;

-- F5. version 乐观锁列：期望 IS_NULLABLE=NO、COLUMN_DEFAULT=0；存量行全部读 0。
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'service_category'
  AND column_name = 'version';

SELECT version, COUNT(*) AS rows_count
FROM service_category
GROUP BY version
ORDER BY version;

-- ============================================================
-- 失败回滚 / 人工核对
-- ============================================================
-- 1) 收紧前（A/B 已执行、C 尚未通过）：
--    * 先备份 service_category；
--    * 人工核对 11 类之外的分类：按平台既定编号规则补 category_code，例如
--      UPDATE service_category SET category_code = '<人工核定CODE>' WHERE id = <id>;
--    * 重跑 C1/C2/C3，全部通过后再执行 D/E；切勿跳过核对直接 MODIFY NOT NULL。
-- 2) D 失败：
--    * MySQL DDL 隐式提交，A/B 的列与回填不会回滚，service_category 仍可用（新列可空）；
--    * 不要重跑整个文件（A 的 ADD COLUMN 会因列已存在报 duplicate column）；
--    * 修正数据后，只重跑 D 与 E；确认成功后按部署 runbook 处理 Flyway 失败记录
--      （flyway repair 清除失败行后，将 V29 以已应用状态登记，或由 DBA 在
--      service_write_schema_history 记录成功版本；不得再次执行 A/B）。
-- 3) E 失败：D 已生效，仅审计表缺失；补建 E 并登记 V29 已应用。
-- 4) 完整回滚（仅限尚未投入生产写入、且已导出 category_code 数据时）：
--    DROP TABLE service_category_audit;
--    ALTER TABLE service_category
--        DROP INDEX idx_service_category_parent,
--        DROP INDEX uk_service_category_code,
--        DROP COLUMN description,
--        DROP COLUMN icon_url,
--        DROP COLUMN version,
--        DROP COLUMN parent_id,
--        DROP COLUMN category_code;
--    注意：回滚会丢失全部 category_code 与 version 乐观锁状态；上线后只能用
--    “修复前进”，不得删除审计表。
-- 5) 部署登记：本迁移需纳入上线部署 runbook 的 opt-in 迁移清单（V29），并由运维/
--    DBA 在隔离库（svcw001_*）先演练；不得在共享生产库默认执行。
--
-- 边界与遗留：
--   * category_code 词法（^[A-Z][A-Z0-9_]{1,31}$）由 B-SVC-02 应用层校验，本 Schema
--     不新增 DB CHECK，避免与 CCR §3.1 的 DDL 不一致。
--   * parent_id 为逻辑外键，不建 FOREIGN KEY（模块边界与既有字典表惯例一致）。
--   * 本迁移不播种 11 条初始字典行（Snowflake id 由应用生成）；全新环境依赖既有
--     部署种子/运营创建流程，见“适用库”。
