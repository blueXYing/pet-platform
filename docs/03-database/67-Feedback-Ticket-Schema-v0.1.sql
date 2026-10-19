-- ============================================================
-- 67 - 反馈工单 Schema v0.1（67号契约：C 端投诉类反馈工单）
-- 2026-10-19。归属：客服/工单域（pet-customer-service-biz 骨架模块首次落业务）。
-- 状态词表冻结 PRD 5.1.28 五值：PENDING/PROCESSING/WAITING_SUPPLEMENT/RESOLVED/CLOSED；
-- V1 只落 PENDING（运营处置面不在切片，CCR-CS-FEEDBACK-001）。
-- 内容/联系方式原文存储、owner-scoped 读（SSOT 无脱敏条款，CCR-CS-FEEDBACK-001 §2）。
-- 事件表 append-only 即工单操作史（V1 仅 SUBMITTED 一值）。
-- 幂等表沿 23号/SQL14 字段结构（模块本地，不跨模块共享命令表）。
-- 无应用启动迁移；测试在隔离库建表。
-- ============================================================

CREATE TABLE feedback_ticket (
    id             BIGINT       NOT NULL COMMENT 'Snowflake PK（工单号，HTTP/JSON String）',
    user_id        BIGINT       NOT NULL COMMENT '提交人（owner-scoped 唯一读界）',
    type           VARCHAR(32)  NOT NULL COMMENT 'FEATURE_SUGGESTION/BUG_REPORT/SERVICE_COMPLAINT/OTHER（PRD 5.1.28 四值，不新增）',
    contact        VARCHAR(64)  NULL COMMENT '联系方式选填（手机号或微信号，原文存储，仅详情返回）',
    content        VARCHAR(1500) NOT NULL COMMENT '反馈内容 1..500 码点（utf8mb4 3 字节上界冗余）',
    status         VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/WAITING_SUPPLEMENT/RESOLVED/CLOSED（词表冻结，V1 恒 PENDING）',
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_feedback_user_created (user_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE feedback_ticket_event (
    id             BIGINT       NOT NULL COMMENT 'Snowflake PK',
    ticket_id      BIGINT       NOT NULL COMMENT 'feedback_ticket.id',
    action         VARCHAR(32)  NOT NULL COMMENT 'SUBMITTED（词表冻结；运营面启用后沿动作词表增补）',
    actor_type     VARCHAR(16)  NOT NULL COMMENT 'USER（V1 唯一值）',
    actor_id       BIGINT       NOT NULL COMMENT '操作者（=提交人 user_id）',
    detail         VARCHAR(255) NULL COMMENT '人读明细（V1 NULL）',
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_feedback_event_ticket (ticket_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 23号公共幂等契约同构物理表（字段结构=SQL14 原样；模块本地命名，不跨模块共享命令表）。
CREATE TABLE feedback_command_idempotency (
    id                 BIGINT        NOT NULL COMMENT 'Snowflake PK',
    request_key        VARCHAR(512)  NOT NULL COMMENT 'namespace|USER|userId|USER_SELF|requestId',
    canonical_version  VARCHAR(16)   NOT NULL COMMENT 'canonical-v1',
    params_sha256      CHAR(64)      NOT NULL COMMENT 'identifier only; bytes are the equality fact',
    params_canonical   MEDIUMBLOB    NOT NULL COMMENT 'protected canonical bytes',
    status             VARCHAR(16)   NOT NULL COMMENT 'RESERVED/SUCCEEDED',
    receipt_json       JSON          NULL COMMENT 'first minimal success receipt',
    created_at         DATETIME(3)   NOT NULL,
    succeeded_at       DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_command_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Supplement 23 command binding; never auto-deleted';
