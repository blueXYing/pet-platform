-- 72-服务分类初始字典种子 v0.1
-- 状态：实现输入（2026-11-02）；与 service-migration/V30__service_category_seed.sql 完全同一套 SQL。
-- 依据：CCR-SVC-CAT-001 §3/§6；74-服务目录页契约 §2.1 初始分类与编号。
--
-- 生产与测试使用同一份数据：11 个标准分类、固定 category_code、sort_no 10..110。
-- 幂等：按 category_code 或 category_name 已存在即跳过；生产后续调整直接改生产库，
-- 不把生产运营数据回写到仓库。执行顺序：70-Service-Category-Dictionary-Schema（或 V29）
-- 先加列/收紧，再执行本文件（或 V30）。
--
-- 固定 id 910001..910011 仅用于字典种子；若目标库已有同 id 的其他分类行，先人工核对再执行。
-- 不写入 icon_url/description：由运营通过既有后台素材上传后填写，避免伪造设计素材。

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910001, 'PET_GROOMING', '宠物美容', NULL, 0, 'ENABLED', 10,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'PET_GROOMING' OR category_name = '宠物美容'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910002, 'DOG_WALKING', '遛狗陪护', NULL, 0, 'ENABLED', 20,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'DOG_WALKING' OR category_name = '遛狗陪护'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910003, 'PET_BOARDING', '宠物寄养', NULL, 0, 'ENABLED', 30,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'PET_BOARDING' OR category_name = '宠物寄养'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910004, 'PET_TRAINING', '宠物训练', NULL, 0, 'ENABLED', 40,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'PET_TRAINING' OR category_name = '宠物训练'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910005, 'HOME_FEEDING', '上门喂养', NULL, 0, 'ENABLED', 50,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'HOME_FEEDING' OR category_name = '上门喂养'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910006, 'VET_ASSISTANT', '兽医助理', NULL, 0, 'ENABLED', 60,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'VET_ASSISTANT' OR category_name = '兽医助理'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910007, 'SMALL_PET_BOARDING', '小宠寄养', NULL, 0, 'ENABLED', 70,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'SMALL_PET_BOARDING' OR category_name = '小宠寄养'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910008, 'EXOTIC_HOME_FEEDING', '异宠上门喂养', NULL, 0, 'ENABLED', 80,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'EXOTIC_HOME_FEEDING' OR category_name = '异宠上门喂养'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910009, 'EXOTIC_HEALTH_CHECK', '异宠健康检查', NULL, 0, 'ENABLED', 90,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'EXOTIC_HEALTH_CHECK' OR category_name = '异宠健康检查'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910010, 'PLANT_CARE', '绿植养护', NULL, 0, 'ENABLED', 100,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'PLANT_CARE' OR category_name = '绿植养护'
);

INSERT INTO service_category
    (id, category_code, category_name, parent_id, version, status, sort_no,
     icon_url, description, created_at, updated_at)
SELECT 910011, 'PLANT_BOARDING', '植物代养', NULL, 0, 'ENABLED', 110,
       NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM service_category
    WHERE category_code = 'PLANT_BOARDING' OR category_name = '植物代养'
);

-- 验收：期望 11 行；已有行时总数可能更大。
SELECT COUNT(*) AS standard_category_rows
FROM service_category
WHERE category_code IN (
    'PET_GROOMING', 'DOG_WALKING', 'PET_BOARDING', 'PET_TRAINING', 'HOME_FEEDING',
    'VET_ASSISTANT', 'SMALL_PET_BOARDING', 'EXOTIC_HOME_FEEDING',
    'EXOTIC_HEALTH_CHECK', 'PLANT_CARE', 'PLANT_BOARDING'
);
