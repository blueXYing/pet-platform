-- 76-首页运营演示数据种子 v0.1
-- 状态：实现输入（2026-11-23，用户裁决）；与 E2E 验收服务的自动种子
--       pet-boot/src/test/.../e2e/E2eCoreLoopServer.java#seedMarketing 是同一套数据。
-- 依据：Figma 61-599「首页」（活动钜惠 / 热门服务 / 宠友圈热门 / 植物＆异宠）；CCR-MKT-003 首页运营内容位。
--
-- 用途：本地 / 演示环境初始化首页运营数据。换机器、换数据库后执行本文件，即得到与 E2E 自动初始化
--       一致的数据。生产环境如需使用，须先由运营确认文案与跳转目标。
-- 执行：mysql -h <host> -P <port> -u <user> -p <数据库名> < docs/03-database/76-首页运营演示数据种子-v0.1.sql
--
-- 前置：marketing_campaign / marketing_campaign_store / marketing_placement / marketing_home_block
--       四张表已存在（63-Marketing-Storage、71-Marketing-Hot-Service-Placement-Schema 及对应 Flyway 迁移）。
--       本文件不在任何 Flyway 扫描路径内（主迁移只扫 classpath:db/migration，营销迁移只扫
--       classpath:db/marketing-migration），不会被自动执行。
--
-- 第一部分（活动钜惠卡片）引用验收环境的种子商家 710301 / 门店 710302；目标库没有这两个 id 时
--       该部分不适用，只执行第二部分（首页内容位，无任何外部依赖）即可。
--
-- 图片：活动卡插画存于对象存储，cover_url 只写 Key（如 marketing/fullcard/xxx.png），公网 URL 由
--       服务端按部署配置拼接——换 bucket / 接 CDN 不需要改这里。
--       内容位的 image_url 留空，由小程序内置的设计稿切图兜底。
--
-- 幂等：按业务键（campaign_code / position_code+campaign_id / block_code）已存在即跳过，不会覆盖
--       运营在后台改过的内容；其他约束违规照常报错，不会被静默吞掉。
--       固定 id 720100..720302 仅用于本种子；目标库已有同 id 的其他行时先人工核对再执行。
--       第一部分与第二部分相互独立，可以只执行其中一部分（连同开头的三个 SET）。
--
-- 时间：起止时间相对执行时刻计算（列存 UTC，有效期 = 执行前 30 天 ~ 执行后 400 天），避免写死
--       日期过期后首页整块消失。需要长期有效时在运营后台调整结束时间。

SET @seed_now   := UTC_TIMESTAMP();
SET @seed_start := DATE_SUB(@seed_now, INTERVAL 30 DAY);
SET @seed_end   := DATE_ADD(@seed_now, INTERVAL 400 DAY);

-- ---------------------------------------------------------------------------
-- 一、活动钜惠：HOME_ACTIVITY 投放位，三张卡片
--    tag_code 是小程序卡片模板码（full_card = 整卡插画铺满），tag_name 是徽章上显示的文字。
--    每张卡片三行：活动 → 参与门店 → 首页投放位。
-- ---------------------------------------------------------------------------

INSERT INTO marketing_campaign
    (id, campaign_code, name, owner_type, owner_merchant_id, owner_store_id,
     campaign_type, participation_mode, status, title, subtitle, tag_code, tag_name,
     cover_url, city_codes, start_at, end_at, sort_weight, version,
     created_by_type, created_by_id, created_at, updated_at)
SELECT 720100, 'MKT-NEW-50', '新客专享·限时5折', 'MERCHANT', 710301, 710302,
       'DISPLAY', 'MERCHANT_ENROLL', 'ONLINE', '新客专享 · 限时 5 折', '新用户下单即享优惠',
       'full_card', '新客专享', 'marketing/fullcard/new-user-clean.png', '["chengdu"]',
       @seed_start, @seed_end, 300, 1, 'ADMIN', 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_campaign WHERE campaign_code = 'MKT-NEW-50');

INSERT INTO marketing_campaign_store
    (id, campaign_id, merchant_id, store_id, source, status, sort_weight, version, created_at, updated_at)
SELECT 720110, 720100, 710301, 710302, 'MERCHANT_OWN', 'ACTIVE', 300, 1, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_campaign_store WHERE campaign_id = 720100 AND store_id = 710302
);

INSERT INTO marketing_placement
    (id, position_code, campaign_id, city_code, sort_weight, start_at, end_at, status, version,
     created_by_id, created_at, updated_at)
SELECT 720200, 'HOME_ACTIVITY', 720100, 'chengdu', 300, @seed_start, @seed_end, 'ENABLED', 1,
       9001, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_placement WHERE position_code = 'HOME_ACTIVITY' AND campaign_id = 720100
);

INSERT INTO marketing_campaign
    (id, campaign_code, name, owner_type, owner_merchant_id, owner_store_id,
     campaign_type, participation_mode, status, title, subtitle, tag_code, tag_name,
     cover_url, city_codes, start_at, end_at, sort_weight, version,
     created_by_type, created_by_id, created_at, updated_at)
SELECT 720101, 'MKT-FIRST-30', '首单立减¥30', 'MERCHANT', 710301, 710302,
       'COUPON', 'MERCHANT_ENROLL', 'ONLINE', '首单立减 ¥30', '新用户下单即享优惠',
       'full_card', '新客专享', 'marketing/fullcard/first-order-clean.png', '["chengdu"]',
       @seed_start, @seed_end, 200, 1, 'ADMIN', 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_campaign WHERE campaign_code = 'MKT-FIRST-30');

INSERT INTO marketing_campaign_store
    (id, campaign_id, merchant_id, store_id, source, status, sort_weight, version, created_at, updated_at)
SELECT 720111, 720101, 710301, 710302, 'MERCHANT_OWN', 'ACTIVE', 200, 1, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_campaign_store WHERE campaign_id = 720101 AND store_id = 710302
);

INSERT INTO marketing_placement
    (id, position_code, campaign_id, city_code, sort_weight, start_at, end_at, status, version,
     created_by_id, created_at, updated_at)
SELECT 720201, 'HOME_ACTIVITY', 720101, 'chengdu', 200, @seed_start, @seed_end, 'ENABLED', 1,
       9001, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_placement WHERE position_code = 'HOME_ACTIVITY' AND campaign_id = 720101
);

INSERT INTO marketing_campaign
    (id, campaign_code, name, owner_type, owner_merchant_id, owner_store_id,
     campaign_type, participation_mode, status, title, subtitle, tag_code, tag_name,
     cover_url, city_codes, start_at, end_at, sort_weight, version,
     created_by_type, created_by_id, created_at, updated_at)
SELECT 720102, 'MKT-GROOM-50', '宠物美容狂欢', 'MERCHANT', 710301, 710302,
       'DISPLAY', 'MERCHANT_ENROLL', 'ONLINE', '宠物美容狂欢', '洗澡美容低至 5 折起',
       'full_card', '限时5折', 'marketing/fullcard/grooming-clean.png', '["chengdu"]',
       @seed_start, @seed_end, 100, 1, 'ADMIN', 9001, @seed_now, @seed_now
WHERE NOT EXISTS (SELECT 1 FROM marketing_campaign WHERE campaign_code = 'MKT-GROOM-50');

INSERT INTO marketing_campaign_store
    (id, campaign_id, merchant_id, store_id, source, status, sort_weight, version, created_at, updated_at)
SELECT 720112, 720102, 710301, 710302, 'MERCHANT_OWN', 'ACTIVE', 100, 1, @seed_now, @seed_now
WHERE NOT EXISTS (
    SELECT 1 FROM marketing_campaign_store WHERE campaign_id = 720102 AND store_id = 710302
);

INSERT INTO marketing_placement
    (id, position_code, campaign_id, city_code, sort_weight, start_at, end_at, status, version,
     created_by_id, created_at, updated_at)
SELECT 720202, 'HOME_ACTIVITY', 720102, 'chengdu', 100, @seed_start, @seed_end, 'ENABLED', 1,
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
-- 验收：期望 3 / 3 / 3 / 3；已存在同业务键的其他行时计数可能更大。
-- ---------------------------------------------------------------------------

SELECT 'campaign' AS seed_table, COUNT(*) AS seed_rows FROM marketing_campaign
WHERE campaign_code IN ('MKT-NEW-50', 'MKT-FIRST-30', 'MKT-GROOM-50')
UNION ALL
SELECT 'campaign_store', COUNT(*) FROM marketing_campaign_store
WHERE campaign_id IN (720100, 720101, 720102) AND store_id = 710302
UNION ALL
SELECT 'placement', COUNT(*) FROM marketing_placement
WHERE position_code = 'HOME_ACTIVITY' AND campaign_id IN (720100, 720101, 720102)
UNION ALL
SELECT 'home_block', COUNT(*) FROM marketing_home_block
WHERE block_code IN ('HB-HOT-SERVICE', 'HB-COMMUNITY-HOT', 'HB-EXOTIC-PLANT');
