-- ============================================================
-- 66 - 用户设置枢纽与账号注销 Schema v0.1（66号契约：注销前置校验读侧 + 注销写路径）
-- 2026-10-19。归属：USER 域（pet-user-biz）。
-- v0.1 口径：同步注销（无冷静期）；本表 append-only。
-- **v0.2 2026-10-19 用户裁决"继续扩展CCR"引入冷静期（默认 7 天，可配）**：
--   请求行升级为状态机行 PENDING →（用户撤销，CAS）CANCELED /（到期 durable 任务，
--   SQL13 ACCOUNT_DELETION_EXEC）ACCEPTED；冷静期内账号恒 ACTIVE（可登录、可用、
--   可撤销），到期任务在行锁事务内重跑前置校验后执行 user_account CANCELED 化，
--   出现阻塞/FROZEN 则自动作废请求（不产生部分注销）。数据保留口径不变（零删除）。
-- 账号状态翻转复用 SQL06 §1 user_account.status 既有词表（ACTIVE/FROZEN/CANCELED）；
-- 阻塞校验结果快照落 blockers_json 供审计回溯。
-- 无应用启动迁移；测试在隔离库建表（同 55/62 号口径）。
-- ============================================================

CREATE TABLE user_deletion_request (
    id                BIGINT       NOT NULL COMMENT 'Snowflake PK（注销单号，HTTP/JSON String）',
    user_id           BIGINT       NOT NULL COMMENT '注销账号（SQL06 user_account.id）',
    request_id        VARCHAR(64)  NOT NULL COMMENT '终结性 X-Request-Id（23号幂等五元组，namespace account.deletion）',
    acknowledged      TINYINT(1)   NOT NULL COMMENT '注销影响确认（请求体 acknowledged=true 原样落审计）',
    status            VARCHAR(16)  NOT NULL COMMENT 'v0.2 状态机：PENDING（冷静期）/CANCELED（用户撤销或到期阻塞自动作废）/ACCEPTED（到期任务已执行 CANCELED 化）',
    blockers_json     JSON         NULL COMMENT '前置校验阻塞明细快照（PENDING/ACCEPTED 行 NULL；到期阻塞自动作废行记快照）',
    cooldown_days     INT          NOT NULL COMMENT '冷静期天数快照（提交时刻 pet.usersettings.deletion.cooldown-days 配置值）',
    effective_at      DATETIME(3)  NOT NULL COMMENT '到期执行时刻 = created_at + cooldown_days（durable 任务 availableAt 同值）',
    canceled_at       DATETIME(3)  NULL COMMENT '撤销/自动作废时刻（status=CANCELED）',
    cancel_request_id VARCHAR(64)  NULL COMMENT '撤销命令终结性 X-Request-Id（namespace account.deletion.cancel；自动作废为 NULL）',
    executed_at       DATETIME(3)  NULL COMMENT 'CANCELED 化执行时刻（status=ACCEPTED）',
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_deletion_request_id (request_id),
    UNIQUE KEY uk_user_deletion_cancel (cancel_request_id),
    KEY idx_user_deletion_user (user_id, created_at),
    KEY idx_user_deletion_due (status, effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
