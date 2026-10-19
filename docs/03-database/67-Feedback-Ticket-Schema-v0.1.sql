-- ============================================================
-- 67 - 反馈工单 Schema v0.1（67号契约：C 端投诉类反馈工单）
-- 2026-10-19。归属：客服/工单域（pet-customer-service-biz 骨架模块首次落业务）。
-- 状态词表冻结 PRD 5.1.28 五值：PENDING/PROCESSING/WAITING_SUPPLEMENT/RESOLVED/CLOSED；
-- v0.1 只落 PENDING；**v0.2 2026-10-19 用户裁决"继续扩展CCR"放开运营处置面**——
-- 状态机开放 PENDING→PROCESSING→RESOLVED 两跳（WAITING_SUPPLEMENT/CLOSED 维持不可达）。
-- 内容/联系方式原文存储、owner-scoped 读 + 运营处置读（工单内容运营侧展示原样，
-- 日志脱敏沿既有——CCR-CS-FEEDBACK-001 v0.2 口径）。
-- 事件表 append-only 即工单操作史（动作词表 v0.2：SUBMITTED/PROCESSING/RESOLVED）。
-- 幂等表沿 23号/SQL14 字段结构（模块本地，不跨模块共享命令表）。
-- 无应用启动迁移；测试在隔离库建表。
-- ============================================================

CREATE TABLE feedback_ticket (
    id             BIGINT       NOT NULL COMMENT 'Snowflake PK（工单号，HTTP/JSON String）',
    user_id        BIGINT       NOT NULL COMMENT '提交人（owner-scoped 唯一读界；运营处置读不限定）',
    type           VARCHAR(32)  NOT NULL COMMENT 'FEATURE_SUGGESTION/BUG_REPORT/SERVICE_COMPLAINT/OTHER（PRD 5.1.28 四值，不新增）',
    contact        VARCHAR(64)  NULL COMMENT '联系方式选填（手机号或微信号，原文存储，仅详情返回）',
    content        VARCHAR(1500) NOT NULL COMMENT '反馈内容 1..500 码点（utf8mb4 3 字节上界冗余）',
    status         VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/WAITING_SUPPLEMENT/RESOLVED/CLOSED（词表冻结；v0.2 状态机开放 PENDING→PROCESSING→RESOLVED）',
    version        BIGINT       NOT NULL DEFAULT 0,
    reply          VARCHAR(1000) NULL COMMENT 'v0.2 运营回复（RESOLVED 一次性写入，1..1000 码点；C 端详情可见）',
    reply_operator_id BIGINT    NULL COMMENT 'v0.2 回复运营者（admin 账号 Snowflake）',
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    replied_at     DATETIME(3)  NULL COMMENT 'v0.2 回复时刻（RESOLVED 写入）',
    PRIMARY KEY (id),
    KEY idx_feedback_user_created (user_id, created_at, id),
    KEY idx_feedback_ops_status (status, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- v0.2 附件绑定表：创建时内核经 31号 PrivateAssetApi.resolveOwned 重证
-- （owner=提交人 + purpose=FEEDBACK_ATTACHMENT + READY），绑定后不再校验资产存续。
CREATE TABLE feedback_ticket_attachment (
    id             BIGINT       NOT NULL COMMENT 'Snowflake PK',
    ticket_id      BIGINT       NOT NULL COMMENT 'feedback_ticket.id',
    asset_id       BIGINT       NOT NULL COMMENT '31号 private_asset.id（purpose=FEEDBACK_ATTACHMENT，READY）',
    sort_no        INT          NOT NULL DEFAULT 0 COMMENT '提交顺序（0..5）',
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_attachment_ticket_asset (ticket_id, asset_id),
    KEY idx_feedback_attachment_ticket (ticket_id, sort_no, asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE feedback_ticket_event (
    id             BIGINT       NOT NULL COMMENT 'Snowflake PK',
    ticket_id      BIGINT       NOT NULL COMMENT 'feedback_ticket.id',
    action         VARCHAR(32)  NOT NULL COMMENT 'v0.1 SUBMITTED；v0.2 增 PROCESSING/RESOLVED（运营处置审计行）',
    actor_type     VARCHAR(16)  NOT NULL COMMENT 'USER（v0.1 唯一值）；v0.2 增 OPERATOR',
    actor_id       BIGINT       NOT NULL COMMENT '操作者（提交人 user_id 或运营者 admin 账号 id）',
    detail         VARCHAR(255) NULL COMMENT '人读明细（回复正文落 feedback_ticket.reply，不入事件行）',
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_feedback_event_ticket (ticket_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 23号公共幂等契约同构物理表（字段结构=SQL14 原样；模块本地命名，不跨模块共享命令表）。
-- v0.2 起同表承载运营处置命令（namespace feedback.process / feedback.resolve，scope OPS_ALL）。
CREATE TABLE feedback_command_idempotency (
    id                 BIGINT        NOT NULL COMMENT 'Snowflake PK',
    request_key        VARCHAR(512)  NOT NULL COMMENT 'namespace|USER|userId|USER_SELF|requestId 或 namespace|OPS|operatorId|OPS_ALL|requestId',
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
