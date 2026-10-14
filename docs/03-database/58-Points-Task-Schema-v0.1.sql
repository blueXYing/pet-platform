-- Pet Platform V1.0 points task schema v0.1 (58号契约：签到+拉新+运营配置)
-- MySQL 8.0+. Sign-in / referral / one-time award state for the points earn side.
-- Points balances and ledger rows stay in SQL06 §9 (points_account / points_ledger);
-- command idempotency bindings stay in SQL14 (command_idempotency). No migration is
-- executed by the application; tests build these tables in isolated databases.

-- ============================================================
-- 1. 运营配置（单行；无行=内置缺省 5/30/50/3/100/100/20）
-- ============================================================

CREATE TABLE IF NOT EXISTS points_task_config (
    id                           BIGINT       NOT NULL COMMENT 'Snowflake PK',
    scope                        VARCHAR(16)  NOT NULL COMMENT '固定 GLOBAL 单行约束',
    sign_in_daily_points         BIGINT       NOT NULL COMMENT '每日签到积分 [1,1000]',
    sign_in_streak_bonus_points  BIGINT       NOT NULL COMMENT '连签7天一次性奖励 [0,1000]',
    invite_per_invitee_points    BIGINT       NOT NULL COMMENT '拉新每人间积分 [0,1000]',
    invite_monthly_reward_cap    BIGINT       NOT NULL COMMENT '拉新每月领取上限 [0,100]',
    invite_milestone_bonus_points BIGINT      NOT NULL COMMENT '邀请3位好友一次性奖励 [0,1000]',
    first_order_points           BIGINT       NOT NULL COMMENT '完成首次下单任务奖励 [0,1000]',
    profile_complete_points      BIGINT       NOT NULL COMMENT '完善资料任务奖励 [0,1000]',
    version                      BIGINT       NOT NULL DEFAULT 0,
    created_at                   DATETIME(3)  NOT NULL,
    updated_at                   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_task_config_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 2. 每日签到（Asia/Shanghai 自然日；每用户每日一行）
-- ============================================================

CREATE TABLE IF NOT EXISTS points_sign_in (
    id            BIGINT       NOT NULL COMMENT 'Snowflake PK',
    user_id       BIGINT       NOT NULL,
    sign_date     DATE         NOT NULL COMMENT 'UTC+8 自然日',
    points        BIGINT       NOT NULL COMMENT '当日发放面额（发放时配置快照）',
    streak_after  BIGINT       NOT NULL COMMENT '签到后连签天数',
    ledger_id     BIGINT       NOT NULL COMMENT 'points_ledger 行',
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_sign_in_user_date (user_id, sign_date),
    KEY idx_points_sign_in_user_date (user_id, sign_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 3. 拉新绑定（每被邀请人终身一行，首绑终局）
-- ============================================================

CREATE TABLE IF NOT EXISTS points_referral_binding (
    id                BIGINT       NOT NULL COMMENT 'Snowflake PK',
    inviter_id        BIGINT       NOT NULL COMMENT '邀请人（积分发放对象）',
    invitee_id        BIGINT       NOT NULL COMMENT '被邀请人（绑定主体）',
    invite_code       VARCHAR(32)  NOT NULL COMMENT '绑定时使用的邀请码（留痕）',
    state             VARCHAR(16)  NOT NULL COMMENT 'BOUND/REWARDED',
    reward_period     VARCHAR(7)   NULL COMMENT '领取归属月 YYYY-MM（月上限计量）',
    rewarded_at       DATETIME(3)  NULL,
    reward_ledger_id  BIGINT       NULL COMMENT '人头奖励 points_ledger 行',
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_referral_invitee (invitee_id),
    KEY idx_points_referral_inviter (inviter_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 4. 一次性任务奖励（里程碑/任务领取终局记录）
-- ============================================================

CREATE TABLE IF NOT EXISTS points_task_award (
    id          BIGINT       NOT NULL COMMENT 'Snowflake PK',
    user_id     BIGINT       NOT NULL,
    task_code   VARCHAR(32)  NOT NULL COMMENT 'SIGN_IN_STREAK_7/INVITE_MILESTONE_3/FIRST_ORDER/PROFILE_COMPLETE',
    points      BIGINT       NOT NULL COMMENT '发放面额快照',
    ledger_id   BIGINT       NOT NULL COMMENT 'points_ledger 行',
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_task_award_user_code (user_id, task_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
