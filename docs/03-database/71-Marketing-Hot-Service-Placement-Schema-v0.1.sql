-- 71-营销热门服务投放位 Schema v0.1
-- 状态：Schema 登记（2026-10-30）；仅文档，不包含 Flyway 迁移文件。
-- 依据：CCR-SVC-CAT-001 §2/§7；71-营销活动中心契约 v0.3（含热门服务补丁）§2.3/§3.1/§4.2；
--       74-服务目录页契约 v0.1 §3.6/§3.7/§9.9-§9.10；CCR-MKT-003。
-- 基线：docs/03-database/63-Marketing-Storage-v0.1.sql 的 marketing_placement /
--       marketing_home_block（本文件不改 63 号正文）。
-- Flyway：由 B-MKT-01 落地 marketing-migration/V36（V33→V34→V35→V36）；本文件不创建 V36。
-- 目标：MySQL 8.0.16+（CHECK 约束需 8.0.16+，DROP CHECK 自 8.0.16 可用）；
--       InnoDB / utf8mb4_0900_ai_ci；DATETIME(3) 一律 UTC。
--
-- 变更范围（仅约束白名单，无列/索引变更）：
--   1. marketing_placement.position_code 白名单增加 HOT_SERVICE_TOP
--      （热门服务二级页顶部 banner 投放位）；
--   2. marketing_home_block.target_type 白名单增加 HOT_SERVICE；
--      并允许 targetType=HOT_SERVICE 时 target_value 为空（除 NONE / HOT_SERVICE 外，
--      仍要求 target_value IS NOT NULL）。
--
-- 兼容性：新白名单是旧白名单的超集，存量行全部继续满足新 CHECK，无需数据回填。
-- 全新环境：先执行 63 号建表，再执行本文件 §1/§2（或由 V36 做等价变更）。
--
-- ============================================================
-- §1. marketing_placement：新增 HOT_SERVICE_TOP
-- ============================================================
ALTER TABLE marketing_placement
    DROP CHECK chk_marketing_placement_position,
    ADD CONSTRAINT chk_marketing_placement_position CHECK (
        position_code IN ('HOME_BANNER','HOME_ACTIVITY','STORE_LIST_TOP','HOT_SERVICE_TOP')
    );

-- 说明：74 号契约 §3.7 已确认 HOT_SERVICE_TOP 全平台最多 1 条 ENABLED；cityCode 只决定
-- 该条 banner 的展示范围。MySQL 无部分唯一索引，且该规则是业务校验，由 B-MKT-01 在
-- 应用层（创建/启用/编辑）校验并返回 409；本 Schema 不新增唯一键/生成列。
-- 现有 idx_marketing_placement_read(position_code, city_code, status, start_at, end_at,
-- sort_weight, id) 已覆盖该投放位读取，无需新增索引。

-- ============================================================
-- §2. marketing_home_block：新增 HOT_SERVICE，并允许其 target_value 为空
-- ============================================================
ALTER TABLE marketing_home_block
    DROP CHECK chk_marketing_home_block_target,
    ADD CONSTRAINT chk_marketing_home_block_target CHECK (
        target_type IN ('SERVICE_DIRECTORY','SERVICE_CATEGORY','STORE','SERVICE','CAMPAIGN','COMMUNITY','MINIAPP_PATH','NONE','HOT_SERVICE')
        AND (target_type IN ('NONE','HOT_SERVICE') OR target_value IS NOT NULL)
    );

-- 说明：targetType=HOT_SERVICE 时点击进入热门服务二级页；targetValue 可为 NULL（或不参与
-- 跳转）。其余类型（SERVICE_DIRECTORY/SERVICE_CATEGORY/STORE/SERVICE/CAMPAIGN/COMMUNITY/
-- MINIAPP_PATH）仍要求 target_value IS NOT NULL；NONE 仍允许为空。存量行无需回填。

-- ============================================================
-- §3. 验证（只读）
-- ============================================================
-- V1. 两个 CHECK 的当前定义（期望包含 HOT_SERVICE_TOP / HOT_SERVICE）。
SELECT constraint_name, check_clause
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_name IN ('chk_marketing_placement_position', 'chk_marketing_home_block_target')
ORDER BY constraint_name;

-- V2. 投放位白名单负例（期望失败：CHECK 拒绝）。
-- INSERT INTO marketing_placement(id, position_code, campaign_id, sort_weight, start_at, end_at,
--     status, version, created_by_id, created_at, updated_at)
-- VALUES (1, 'NOT_A_POSITION', 1, 0, '2026-10-30 00:00:00.000', '2026-10-31 00:00:00.000',
--     'DRAFT', 1, 1, '2026-10-30 00:00:00.000', '2026-10-30 00:00:00.000');

-- V3. 内容位负例（期望失败）：非 HOT_SERVICE/NONE 类型未填 target_value。
-- INSERT INTO marketing_home_block(id, block_code, position_code, template_code, title,
--     target_type, target_value, sort_weight, start_at, end_at, status, version,
--     created_by_id, created_at, updated_at)
-- VALUES (1, 'b1', 'HOME_SERVICE', 'HOT_SERVICE_FEATURE', 't',
--     'SERVICE_DIRECTORY', NULL, 0, '2026-10-30 00:00:00.000', '2026-10-31 00:00:00.000',
--     'DRAFT', 1, 1, '2026-10-30 00:00:00.000', '2026-10-30 00:00:00.000');

-- V4. 正例：HOT_SERVICE target_value 允许为空。
-- INSERT INTO marketing_home_block(id, block_code, position_code, template_code, title,
--     target_type, target_value, sort_weight, start_at, end_at, status, version,
--     created_by_id, created_at, updated_at)
-- VALUES (2, 'b2', 'HOME_SERVICE', 'HOT_SERVICE_FEATURE', 't',
--     'HOT_SERVICE', NULL, 0, '2026-10-30 00:00:00.000', '2026-10-31 00:00:00.000',
--     'DRAFT', 1, 1, '2026-10-30 00:00:00.000', '2026-10-30 00:00:00.000');

-- V5. 运营核对：HOT_SERVICE_TOP 启用条数期望为 0 或 1（应用层约束；>1 为违规数据）。
SELECT COUNT(*) AS enabled_hot_service_top
FROM marketing_placement
WHERE position_code = 'HOT_SERVICE_TOP'
  AND status = 'ENABLED';

-- ============================================================
-- §4. 回滚 / 失败处理
-- ============================================================
-- 两条 ALTER 各自隐式提交；若第二条失败，第一条已生效，可单独重试第二条。回滚前必须
-- 先清理使用新枚举的数据，否则旧 CHECK 会拒绝存量行：
--   UPDATE marketing_placement SET status = 'DISABLED' WHERE position_code = 'HOT_SERVICE_TOP';
--   DELETE FROM marketing_home_block WHERE target_type = 'HOT_SERVICE';
--   ALTER TABLE marketing_home_block
--       DROP CHECK chk_marketing_home_block_target,
--       ADD CONSTRAINT chk_marketing_home_block_target CHECK (
--           target_type IN ('SERVICE_DIRECTORY','SERVICE_CATEGORY','STORE','SERVICE','CAMPAIGN','COMMUNITY','MINIAPP_PATH','NONE')
--           AND (target_type = 'NONE' OR target_value IS NOT NULL)
--       );
--   ALTER TABLE marketing_placement
--       DROP CHECK chk_marketing_placement_position,
--       ADD CONSTRAINT chk_marketing_placement_position CHECK (
--           position_code IN ('HOME_BANNER','HOME_ACTIVITY','STORE_LIST_TOP')
--       );
-- 生产回滚会丢失投放位/内容位配置，优先“修复前进”；删除/停用前先导出相关行。
-- V36 的落地与失败登记由 B-MKT-01 在其迁移说明中负责，本文件只登记 Schema 口径。
