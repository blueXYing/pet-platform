-- ============================================================
-- 66 - 用户设置枢纽与账号注销 Schema v0.1（66号契约：注销前置校验读侧 + 注销写路径）
-- 2026-10-19。归属：USER 域（pet-user-biz）。
-- 最小实现口径 CCR-USR-DELETION-001：同步注销（无冷静期/申请中态/无清理 AsyncTask）；
-- 本表 append-only——注销审计事实行，永不 UPDATE/DELETE。
-- 账号状态翻转复用 SQL06 §1 user_account.status 既有词表（ACTIVE/FROZEN/CANCELED），
-- 不改核心表结构；阻塞校验结果快照落 blockers_json 供审计回溯。
-- 无应用启动迁移；测试在隔离库建表（同 55/62 号口径）。
-- ============================================================

CREATE TABLE user_deletion_request (
    id             BIGINT       NOT NULL COMMENT 'Snowflake PK',
    user_id        BIGINT       NOT NULL COMMENT '注销账号（SQL06 user_account.id）',
    request_id     VARCHAR(64)  NOT NULL COMMENT '终结性 X-Request-Id（23号幂等五元组，namespace account.deletion）',
    acknowledged   TINYINT(1)   NOT NULL COMMENT '注销影响确认（请求体 acknowledged=true 原样落审计）',
    outcome        VARCHAR(16)  NOT NULL COMMENT 'ACCEPTED（V1 唯一终值；词表预留 REJECTED/CANCELED_PENDING 不使用）',
    blockers_json  JSON         NULL COMMENT '前置校验阻塞明细快照（ACCEPTED 行恒 NULL；词表见 66号 §3）',
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_deletion_request_id (request_id),
    KEY idx_user_deletion_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
