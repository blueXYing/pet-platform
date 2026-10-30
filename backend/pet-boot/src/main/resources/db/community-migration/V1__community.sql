-- 64-Community-Storage-v0.1.sql
-- 宠友圈社区核心域（CCR-COM-001）。MySQL 8.0+，utf8mb4。
-- 生产默认不执行，由 community 模块 opt-in Flyway 装配；所有 ID 为 Snowflake BIGINT，
-- HTTP/JSON 使用十进制 String。社区图片默认数据库 BLOB 回退；配置 OSS_* 时改存 object_key。
-- 社区积分任务不在本切片：COMMUNITY_POST 继续 NOT_OPEN。

CREATE TABLE community_topic (
    id              BIGINT NOT NULL,
    topic_code      VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name            VARCHAR(20) NOT NULL,
    description     VARCHAR(200) NULL,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sort_weight     INT NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_topic_code (topic_code),
    KEY idx_community_topic_enabled (status, sort_weight, id),
    CONSTRAINT chk_community_topic_status CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT chk_community_topic_sort CHECK (sort_weight BETWEEN 0 AND 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区话题字典（运营维护）';

CREATE TABLE community_media (
    id              BIGINT NOT NULL,
    owner_user_id   BIGINT NOT NULL,
    content_type    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bytes           BIGINT NOT NULL,
    width           INT NOT NULL,
    height          INT NOT NULL,
    sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key      VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content         LONGBLOB NULL,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_community_media_owner (owner_user_id, created_at, id),
    KEY idx_community_media_sha (sha256),
    CONSTRAINT chk_community_media_bytes CHECK (bytes BETWEEN 1 AND 2097152),
    CONSTRAINT chk_community_media_size CHECK (width BETWEEN 1 AND 2000 AND height BETWEEN 1 AND 2000),
    CONSTRAINT chk_community_media_status CHECK (status IN ('ACTIVE','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区公开图片素材（OSS 优先，数据库回退）';

CREATE TABLE community_post (
    id                  BIGINT NOT NULL,
    author_user_id      BIGINT NOT NULL,
    title               VARCHAR(60) NULL,
    content             VARCHAR(2000) NOT NULL,
    topic_id            BIGINT NULL,
    image_ids           JSON NULL,
    visibility          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC',
    status              VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    like_count          INT NOT NULL DEFAULT 0,
    comment_count       INT NOT NULL DEFAULT 0,
    favorite_count      INT NOT NULL DEFAULT 0,
    view_count          INT NOT NULL DEFAULT 0,
    version             INT NOT NULL DEFAULT 1,
    published_at        DATETIME(3) NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_community_post_feed (status, visibility, published_at, id),
    KEY idx_community_post_hot (status, visibility, like_count, comment_count, favorite_count, published_at),
    KEY idx_community_post_author (author_user_id, status, published_at, id),
    KEY idx_community_post_topic (topic_id, status, published_at, id),
    CONSTRAINT chk_community_post_visibility CHECK (visibility IN ('PUBLIC','PRIVATE')),
    CONSTRAINT chk_community_post_status CHECK (status IN ('PENDING_REVIEW','PUBLISHED','OFFLINE','DELETED')),
    CONSTRAINT chk_community_post_counts CHECK (
        like_count >= 0 AND comment_count >= 0 AND favorite_count >= 0 AND view_count >= 0
    ),
    CONSTRAINT chk_community_post_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区动态（文字 + 图片 mediaId；软删除）';

CREATE TABLE community_post_like (
    id              BIGINT NOT NULL,
    post_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_post_like (post_id, user_id),
    KEY idx_community_post_like_user (user_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='动态点赞（唯一约束保证重复点赞不重复计数）';

CREATE TABLE community_post_favorite (
    id              BIGINT NOT NULL,
    post_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_post_favorite (post_id, user_id),
    KEY idx_community_post_favorite_user (user_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='动态收藏';

CREATE TABLE community_follow (
    id                  BIGINT NOT NULL,
    follower_user_id    BIGINT NOT NULL,
    followee_user_id    BIGINT NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_follow (follower_user_id, followee_user_id),
    KEY idx_community_follow_followee (followee_user_id, follower_user_id),
    CONSTRAINT chk_community_follow_self CHECK (follower_user_id <> followee_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='关注关系（不允许关注自己）';

CREATE TABLE community_comment (
    id              BIGINT NOT NULL,
    post_id         BIGINT NOT NULL,
    author_user_id  BIGINT NOT NULL,
    content         VARCHAR(500) NOT NULL,
    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_community_comment_post (post_id, status, created_at, id),
    KEY idx_community_comment_author (author_user_id, created_at, id),
    CONSTRAINT chk_community_comment_status CHECK (status IN ('PUBLISHED','OFFLINE','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区评论（V1 单层评论）';

CREATE TABLE community_report (
    id                  BIGINT NOT NULL,
    target_type         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id           BIGINT NOT NULL,
    reporter_user_id    BIGINT NOT NULL,
    reason_code         VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_text         VARCHAR(200) NULL,
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    handled_by          BIGINT NULL,
    handled_at          DATETIME(3) NULL,
    handling_note       VARCHAR(200) NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_report_once (target_type, target_id, reporter_user_id),
    KEY idx_community_report_status (status, created_at, id),
    CONSTRAINT chk_community_report_target CHECK (target_type IN ('POST','COMMENT')),
    CONSTRAINT chk_community_report_status CHECK (status IN ('PENDING','RESOLVED','DISMISSED')),
    CONSTRAINT chk_community_report_reason CHECK (reason_code IN (
        'SPAM','PORN','VIOLENCE','ILLEGAL','HARASSMENT','FALSE_INFO','OTHER'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区举报（同一用户同一对象仅一次）';

CREATE TABLE community_audit (
    id              BIGINT NOT NULL,
    target_type     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id       BIGINT NOT NULL,
    action_type     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status       VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_type   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id     BIGINT NOT NULL,
    reason          VARCHAR(500) NULL,
    request_id      VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_audit_request (request_id),
    KEY idx_community_audit_target (target_type, target_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区审核/处置 append-only 审计';

CREATE TABLE community_command_binding (
    id                  BIGINT NOT NULL,
    request_key         VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_type        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_sha256       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    params_canonical    BLOB NOT NULL,
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json        JSON NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_command_request (request_key),
    CONSTRAINT chk_community_command_status CHECK (status IN ('IN_PROGRESS','SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区写操作 requestId 幂等绑定';

-- 运营初始化话题（可按运营台维护调整；不构成产品规则）
INSERT INTO community_topic (id, topic_code, name, description, status, sort_weight, created_at, updated_at)
VALUES
    (1, 'chengdu-pets', '成都狗友会', '成都同城宠友交流', 'ENABLED', 10, NOW(3), NOW(3)),
    (2, 'chengdu', '成都', '成都同城', 'ENABLED', 20, NOW(3), NOW(3)),
    (3, 'cats', '猫咪', '猫咪日常', 'ENABLED', 30, NOW(3), NOW(3)),
    (4, 'grooming', '美容洗护', '洗护美容分享', 'ENABLED', 40, NOW(3), NOW(3)),
    (5, 'exotic', '异宠植物', '异宠与绿植养护', 'ENABLED', 50, NOW(3), NOW(3));
