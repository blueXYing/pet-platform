-- 76-首页运营演示数据种子 v0.2
-- 状态：实现输入。v0.1 = 2026-11-23 用户裁决（42 号 §十）；v0.2 = 2026-11-27 用户裁决（42 号 §十二）。
--       与 E2E 验收服务的自动种子 pet-boot/src/test/.../e2e/E2eCoreLoopServer.java#seedMarketing
--       是同一套数据，两处必须同步修改（DefaultMarketingSeedTest 会比对两处落库结果）。
-- 依据：Figma 61-599「首页」（活动钜惠 / 热门服务 / 宠友圈热门 / 植物＆异宠）；CCR-MKT-003 首页运营内容位。
--
-- 用途：所有环境（本地 / 测试 / 生产）的首页默认运营数据，已列入部署 Runbook §3.2 的建库清单；
--       第一版上线即带这套数据。
-- 执行：mysql --default-character-set=utf8mb4 -h <host> -P <port> -u <user> -p <数据库名> < docs/03-database/76-首页运营演示数据种子-v0.1.sql
--       整份文件在同一个会话里执行（用到会话变量）。
--
-- 前置：marketing_campaign / marketing_campaign_store / marketing_placement / marketing_home_block
--       四张表已存在（63-Marketing-Storage、71-Marketing-Hot-Service-Placement-Schema 及对应 Flyway 迁移）。
--       本文件不在任何 Flyway 扫描路径内（主迁移只扫 classpath:db/migration，营销迁移只扫
--       classpath:db/marketing-migration），不会被自动执行。
--
-- 归属：三张活动卡是平台活动（owner_type='PLATFORM'），不关联任何商家 / 门店，也不写
--       marketing_campaign_store 行；全部城市生效（活动 city_codes='[]'，投放位 city_code 为空）。
--       文案沿用 v0.1——2026-11-27 用户裁决：三张默认活动卡暂无商家 / 券兜底，仍按原文案展示。
--
-- 图片：活动卡插画固定取自对象存储 bucket mtxoss2，cover_url 写完整 https 地址（2026-11-27 用户裁决；
--       完整地址由服务端原样返回）。换 bucket / 接 CDN 时只改下方 @seed_bucket 一处，第三部分会把仍是
--       旧 Key 的行改成新地址。内容位的 image_url 留空，由小程序内置的设计稿切图兜底。
--
-- 幂等 / 升级：按业务键已存在的行不重复插入。第三部分把已执行过 v0.1 的库升级为 v0.2 的形态，
--       只按「固定 id + 业务码 + v0.1 原值」命中——运营在后台改过的字段不会被覆盖，这类行会出现在
--       末尾的"未自动升级"查询里，需要人工确认归属。
--       固定 id 720100..720302 仅用于本种子；目标库已有同 id 的其他行时先人工核对再执行。
--       第一、二部分相互独立，可单独执行（连同开头的四个 SET）；第三部分须在第一、二部分之后执行。
--
-- 时间：start_at = 执行前 30 天，end_at = 2099-12-31（默认数据长期有效，需要下线时在运营后台
--       暂停 / 结束活动）；列存 UTC。v0.1 的结束时间是执行后 400 天，第三部分会把没被动过的
--       v0.1 行一并延长。

SET @seed_now    := UTC_TIMESTAMP();
SET @seed_start  := DATE_SUB(@seed_now, INTERVAL 30 DAY);
SET @seed_end    := '2099-12-31 00:00:00.000';
SET @seed_bucket := 'https://mtxoss2.oss-cn-hangzhou.aliyuncs.com/marketing/fullcard/';

-- ---------------------------------------------------------------------------
-- 一、活动钜惠：HOME_ACTIVITY 投放位，三张卡片
--    tag_code 是小程序卡片模板码（full_card = 整卡插画铺满），tag_name 是徽章上显示的文字。
--    每张卡片两行：平台活动 → 首页投放位。全部是纯展示活动（DISPLAY），不挂商家、不挂券。
-- ---------------------------------------------------------------------------

INSERT INTO marketing_campaign
    (id, campaign_code, name, owner_type, owner_merchant_id, owner_store_id,
     campaign_type, participation_mode, status, title, subtitle, tag_code, tag_name,
     cover_url, city_codes, start_at, end_at, sort_weight, version,
     created_by_type, created_by_id, created_at, updated_at)
SELECT 720100, 'MKT-NEW-50', '新客专享·限时5折', 'PLATFORM', NULL, NULL,
       'DISPLAY', 'PLATFORM_SELECTED', 'ONLINE', '新客专享 · 限时 5 折', '新用户下单即享优惠',
       'full_card', '新客专享', CONCAT(@seed_bucket, 'new-user-clean.png'), '[]',
       @seed_start, @seed_end, 300, 1, 'ADMIN', 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_campaign WHERE campaign_code = 'MKT-NEW-50');

INSERT INTO marketing_placement
    (id, position_code, campaign_id, city_code, sort_weight, start_at, end_at, status, version,
     created_by_id, created_at, updated_at)
SELECT 720200, 'HOME_ACTIVITY', 720100, NULL, 300, @seed_start, @seed_end, 'ENABLED', 1,
       9001, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_placement WHERE position_code = 'HOME_ACTIVITY' AND campaign_id = 720100
);

INSERT INTO marketing_campaign
    (id, campaign_code, name, owner_type, owner_merchant_id, owner_store_id,
     campaign_type, participation_mode, status, title, subtitle, tag_code, tag_name,
     cover_url, city_codes, start_at, end_at, sort_weight, version,
     created_by_type, created_by_id, created_at, updated_at)
SELECT 720101, 'MKT-FIRST-30', '首单立减¥30', 'PLATFORM', NULL, NULL,
       'DISPLAY', 'PLATFORM_SELECTED', 'ONLINE', '首单立减 ¥30', '新用户下单即享优惠',
       'full_card', '新客专享', CONCAT(@seed_bucket, 'first-order-clean.png'), '[]',
       @seed_start, @seed_end, 200, 1, 'ADMIN', 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_campaign WHERE campaign_code = 'MKT-FIRST-30');

INSERT INTO marketing_placement
    (id, position_code, campaign_id, city_code, sort_weight, start_at, end_at, status, version,
     created_by_id, created_at, updated_at)
SELECT 720201, 'HOME_ACTIVITY', 720101, NULL, 200, @seed_start, @seed_end, 'ENABLED', 1,
       9001, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_placement WHERE position_code = 'HOME_ACTIVITY' AND campaign_id = 720101
);

INSERT INTO marketing_campaign
    (id, campaign_code, name, owner_type, owner_merchant_id, owner_store_id,
     campaign_type, participation_mode, status, title, subtitle, tag_code, tag_name,
     cover_url, city_codes, start_at, end_at, sort_weight, version,
     created_by_type, created_by_id, created_at, updated_at)
SELECT 720102, 'MKT-GROOM-50', '宠物美容狂欢', 'PLATFORM', NULL, NULL,
       'DISPLAY', 'PLATFORM_SELECTED', 'ONLINE', '宠物美容狂欢', '洗澡美容低至 5 折起',
       'full_card', '限时5折', CONCAT(@seed_bucket, 'grooming-clean.png'), '[]',
       @seed_start, @seed_end, 100, 1, 'ADMIN', 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_campaign WHERE campaign_code = 'MKT-GROOM-50');

INSERT INTO marketing_placement
    (id, position_code, campaign_id, city_code, sort_weight, start_at, end_at, status, version,
     created_by_id, created_at, updated_at)
SELECT 720202, 'HOME_ACTIVITY', 720102, NULL, 100, @seed_start, @seed_end, 'ENABLED', 1,
       9001, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_placement WHERE position_code = 'HOME_ACTIVITY' AND campaign_id = 720102
);

-- ---------------------------------------------------------------------------
-- 二、首页内容位：热门服务 / 宠友圈热门 / 植物＆异宠（CCR-MKT-003，无外部依赖）
--    title / subtitle 按设计稿的换行拆成两行。city_code 为空 = 全部城市生效。
--    target_type / target_value 取后端约定：HOT_SERVICE（热门服务页）与 NONE 不需要目标值，
--    COMMUNITY（宠友圈 tab）缺省为 HOT。image_url 留空 → 小程序内置切图。
-- ---------------------------------------------------------------------------

INSERT INTO marketing_home_block
    (id, block_code, position_code, template_code, title, subtitle, tag_name, image_url,
     target_type, target_value, city_code, sponsor_merchant_id, sponsor_store_id,
     sort_weight, start_at, end_at, status, version, created_by_id, created_at, updated_at)
SELECT 720300, 'HB-HOT-SERVICE', 'HOME_SERVICE', 'HOT_SERVICE_FEATURE',
       '上门喂养、遛狗、洗护、医疗', '附近宠托师一键预约', '热门服务', NULL,
       'HOT_SERVICE', NULL, NULL, NULL, NULL,
       300, @seed_start, @seed_end, 'ENABLED', 1, 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_home_block WHERE block_code = 'HB-HOT-SERVICE');

INSERT INTO marketing_home_block
    (id, block_code, position_code, template_code, title, subtitle, tag_name, image_url,
     target_type, target_value, city_code, sponsor_merchant_id, sponsor_store_id,
     sort_weight, start_at, end_at, status, version, created_by_id, created_at, updated_at)
SELECT 720301, 'HB-COMMUNITY-HOT', 'HOME_COMMUNITY', 'COMMUNITY_HOT',
       '发动态、晒萌宠、', '找同城伙伴', '宠友圈热门', NULL,
       'COMMUNITY', 'HOT', NULL, NULL, NULL,
       200, @seed_start, @seed_end, 'ENABLED', 1, 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_home_block WHERE block_code = 'HB-COMMUNITY-HOT');

INSERT INTO marketing_home_block
    (id, block_code, position_code, template_code, title, subtitle, tag_name, image_url,
     target_type, target_value, city_code, sponsor_merchant_id, sponsor_store_id,
     sort_weight, start_at, end_at, status, version, created_by_id, created_at, updated_at)
SELECT 720302, 'HB-EXOTIC-PLANT', 'HOME_EXOTIC_PLANT', 'EXOTIC_PLANT',
       '除了猫猫狗狗，我们同样', '呵护您的小宠与绿植', '植物＆异宠', NULL,
       'NONE', NULL, NULL, NULL, NULL,
       100, @seed_start, @seed_end, 'ENABLED', 1, 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_home_block WHERE block_code = 'HB-EXOTIC-PLANT');

-- ---------------------------------------------------------------------------
-- 三、存量修正：把已执行过 v0.1 的库升级为 v0.2
--    v0.1 的三张活动归商户 710301 / 门店 710302、参与方式 MERCHANT_ENROLL、限成都、cover_url 存 Key、
--    结束时间 = 创建时间 + 400 天。以下语句只命中「固定 id + 业务码 + v0.1 原值」的行：
--    全新库执行本部分不改动任何行；运营在后台改过的字段保持原样。
-- ---------------------------------------------------------------------------

-- 3.1 归属改为平台、去掉招商、放开城市。运营配置过报名期的活动不动（那已是真正的招商活动）。
UPDATE marketing_campaign
SET owner_type = 'PLATFORM', owner_merchant_id = NULL, owner_store_id = NULL,
    participation_mode = 'PLATFORM_SELECTED', city_codes = '[]', updated_at = @seed_now
WHERE ((id = 720100 AND campaign_code = 'MKT-NEW-50')
    OR (id = 720101 AND campaign_code = 'MKT-FIRST-30')
    OR (id = 720102 AND campaign_code = 'MKT-GROOM-50'))
  AND owner_type = 'MERCHANT' AND owner_merchant_id = 710301 AND owner_store_id = 710302
  AND participation_mode = 'MERCHANT_ENROLL'
  AND enroll_start_at IS NULL AND enroll_end_at IS NULL;

-- 3.2 没挂券的"券活动"改为纯展示活动（v0.1 的 MKT-FIRST-30 是 COUPON 却没有券）。已挂券的不动。
UPDATE marketing_campaign
SET campaign_type = 'DISPLAY', updated_at = @seed_now
WHERE id IN (720100, 720101, 720102)
  AND campaign_code IN ('MKT-NEW-50', 'MKT-FIRST-30', 'MKT-GROOM-50')
  AND campaign_type = 'COUPON' AND coupon_campaign_id IS NULL;

-- 3.3 插画从 Key 改成固定 bucket 的完整地址。运营换过图的行不动。
UPDATE marketing_campaign
SET cover_url = CONCAT(@seed_bucket, 'new-user-clean.png'), updated_at = @seed_now
WHERE id = 720100 AND campaign_code = 'MKT-NEW-50'
  AND cover_url = 'marketing/fullcard/new-user-clean.png';

UPDATE marketing_campaign
SET cover_url = CONCAT(@seed_bucket, 'first-order-clean.png'), updated_at = @seed_now
WHERE id = 720101 AND campaign_code = 'MKT-FIRST-30'
  AND cover_url = 'marketing/fullcard/first-order-clean.png';

UPDATE marketing_campaign
SET cover_url = CONCAT(@seed_bucket, 'grooming-clean.png'), updated_at = @seed_now
WHERE id = 720102 AND campaign_code = 'MKT-GROOM-50'
  AND cover_url = 'marketing/fullcard/grooming-clean.png';

-- 3.4 删除 v0.1 写入的三行门店关联（只删本种子的行；运营另选的门店保留）。
DELETE FROM marketing_campaign_store
WHERE id IN (720110, 720111, 720112)
  AND campaign_id IN (720100, 720101, 720102)
  AND merchant_id = 710301 AND store_id = 710302 AND source = 'MERCHANT_OWN';

-- 3.5 投放位放开城市。
UPDATE marketing_placement
SET city_code = NULL, updated_at = @seed_now
WHERE id IN (720200, 720201, 720202) AND position_code = 'HOME_ACTIVITY'
  AND campaign_id IN (720100, 720101, 720102) AND city_code = 'chengdu';

-- 3.6 有效期延长为长期：只动仍是 v0.1 默认窗口（创建时间 + 400 天）的行。
UPDATE marketing_campaign
SET end_at = @seed_end, updated_at = @seed_now
WHERE id IN (720100, 720101, 720102)
  AND campaign_code IN ('MKT-NEW-50', 'MKT-FIRST-30', 'MKT-GROOM-50')
  AND end_at = DATE_ADD(created_at, INTERVAL 400 DAY);

UPDATE marketing_placement
SET end_at = @seed_end, updated_at = @seed_now
WHERE id IN (720200, 720201, 720202) AND position_code = 'HOME_ACTIVITY'
  AND campaign_id IN (720100, 720101, 720102)
  AND end_at = DATE_ADD(created_at, INTERVAL 400 DAY);

UPDATE marketing_home_block
SET end_at = @seed_end, updated_at = @seed_now
WHERE id IN (720300, 720301, 720302)
  AND block_code IN ('HB-HOT-SERVICE', 'HB-COMMUNITY-HOT', 'HB-EXOTIC-PLANT')
  AND end_at = DATE_ADD(created_at, INTERVAL 400 DAY);

-- ---------------------------------------------------------------------------
-- 验收：第一段期望 3 / 0 / 3 / 3（campaign_store 只数 v0.1 的三行；运营另选的门店不计）。
--       第二段列出三张活动卡的现状；第三段"未自动升级"期望为空——有行说明运营改过，需人工确认。
-- ---------------------------------------------------------------------------

SELECT 'campaign' AS seed_table, COUNT(*) AS seed_rows FROM marketing_campaign
WHERE campaign_code IN ('MKT-NEW-50', 'MKT-FIRST-30', 'MKT-GROOM-50')
UNION ALL
SELECT 'campaign_store', COUNT(*) FROM marketing_campaign_store
WHERE id IN (720110, 720111, 720112)
UNION ALL
SELECT 'placement', COUNT(*) FROM marketing_placement
WHERE position_code = 'HOME_ACTIVITY' AND campaign_id IN (720100, 720101, 720102)
UNION ALL
SELECT 'home_block', COUNT(*) FROM marketing_home_block
WHERE block_code IN ('HB-HOT-SERVICE', 'HB-COMMUNITY-HOT', 'HB-EXOTIC-PLANT');

SELECT id, campaign_code, owner_type, campaign_type, participation_mode, status, city_codes,
       cover_url, DATE(start_at) AS start_at, DATE(end_at) AS end_at
FROM marketing_campaign
WHERE campaign_code IN ('MKT-NEW-50', 'MKT-FIRST-30', 'MKT-GROOM-50')
ORDER BY id;

SELECT id, campaign_code, owner_type, campaign_type, participation_mode, enroll_start_at,
       enroll_end_at, coupon_campaign_id, cover_url, DATE(end_at) AS end_at
FROM marketing_campaign
WHERE id IN (720100, 720101, 720102)
  AND (owner_type <> 'PLATFORM' OR owner_merchant_id IS NOT NULL OR owner_store_id IS NOT NULL
       OR campaign_type <> 'DISPLAY' OR participation_mode <> 'PLATFORM_SELECTED'
       OR city_codes IS NOT NULL AND JSON_LENGTH(city_codes) > 0
       OR cover_url NOT LIKE CONCAT(@seed_bucket, '%'))
ORDER BY id;
