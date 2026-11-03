-- 71 号营销活动中心契约 v0.3（热门服务补丁，CCR-SVC-CAT-001 §2/§7）：
-- 投放位白名单增加 HOT_SERVICE_TOP（热门服务二级页顶部 banner）；首页内容位落地类型
-- 增加 HOT_SERVICE，并允许该类型 target_value 为空。Schema 口径登记见
-- docs/03-database/71-Marketing-Hot-Service-Placement-Schema-v0.1.sql（本文件为其 Flyway 落地）。
-- 新白名单是旧白名单的超集，存量行全部继续满足新 CHECK，无需数据回填。
-- 全平台 HOT_SERVICE_TOP 最多 1 条 ENABLED 由生成列 + 唯一键兜底（应用层仍返回 409
-- PLACEMENT_LIMIT_EXCEEDED，避免并发启用绕过上限）。

ALTER TABLE marketing_placement
    DROP CHECK chk_marketing_placement_position,
    ADD CONSTRAINT chk_marketing_placement_position CHECK (
        position_code IN ('HOME_BANNER','HOME_ACTIVITY','STORE_LIST_TOP','HOT_SERVICE_TOP')
    ),
    ADD COLUMN hot_service_top_singleton TINYINT GENERATED ALWAYS AS (
        CASE WHEN position_code = 'HOT_SERVICE_TOP' AND status = 'ENABLED' THEN 1 ELSE NULL END
    ) STORED COMMENT 'HOT_SERVICE_TOP 全平台单例：仅启用行取1，其余NULL；唯一键兜底';

ALTER TABLE marketing_placement
    ADD UNIQUE KEY uk_marketing_placement_hot_top_singleton (hot_service_top_singleton);

ALTER TABLE marketing_home_block
    DROP CHECK chk_marketing_home_block_target,
    ADD CONSTRAINT chk_marketing_home_block_target CHECK (
        target_type IN ('SERVICE_DIRECTORY','SERVICE_CATEGORY','STORE','SERVICE','CAMPAIGN',
                        'COMMUNITY','MINIAPP_PATH','NONE','HOT_SERVICE')
        AND (target_type IN ('NONE','HOT_SERVICE') OR target_value IS NOT NULL)
    );
