-- ============================================================
-- 68 - 宠物健康记录 Schema v0.1（2026-10-19 用户裁决：疫苗/驱虫记录回补，
-- 推翻登记表 §4.11 原范围排除登记。归属：USER 域（pet-user-biz）。
-- 结构镜像 user_address（SQL55 §0）/ user_pet（SQL06 §0）：Snowflake BIGINT
-- 主键、软删除 status、record_type 分面。
-- 字段口径 = PRD 5.1.3 疫苗/驱虫记录字段表（设计稿 96:2580/96:3006 的
-- "接种状态" chips 为读侧展示态，不入库；附件图片 V1 不做，偏差登记）。
-- 隐私口径：健康信息默认仅用户本人可见（PRD 5.1.3 业务规则）；
-- 审计表不含健康内容明文（仅 id/action/request 元数据，35号同款）。
-- 无下游反应：无事件/outbox 订阅方，写路径 = 幂等 + 审计 同事务。
-- ============================================================

CREATE TABLE user_pet_health_record (
    id                   BIGINT        NOT NULL,
    user_id              BIGINT        NOT NULL COMMENT '属主用户,冗余存储加速归属判定',
    pet_id               BIGINT        NOT NULL,
    record_type          VARCHAR(16)   NOT NULL COMMENT 'VACCINATION/DEWORMING',
    record_date          DATE          NOT NULL COMMENT '接种日期/驱虫日期,不得晚于当天',
    vaccine_name         VARCHAR(50)   NULL COMMENT '疫苗名称,1~50码点,VACCINATION 必填',
    dose_number          INT           NULL COMMENT '接种针次,1~20 正整数,VACCINATION 可选',
    deworming_type       VARCHAR(24)   NULL COMMENT 'EXTERNAL/INTERNAL/INTERNAL_EXTERNAL,DEWORMING 必填',
    product_name         VARCHAR(50)   NULL COMMENT '产品名称,≤50码点,DEWORMING 可选',
    next_record_date     DATE          NULL COMMENT '下次接种/驱虫日期,不得早于 record_date',
    note                 VARCHAR(200)  NULL COMMENT '备注,≤200码点',
    status               VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED(软删除,列表不出现)',
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           DATETIME(3)   NOT NULL,
    updated_at           DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_pet_health_record_pet (pet_id, record_type, status, record_date),
    KEY idx_pet_health_record_user (user_id, status),
    CONSTRAINT chk_pet_health_record_type CHECK (record_type IN ('VACCINATION','DEWORMING')),
    CONSTRAINT chk_pet_health_record_deworm CHECK (
        record_type <> 'DEWORMING'
        OR (deworming_type IN ('EXTERNAL','INTERNAL','INTERNAL_EXTERNAL'))
    ),
    CONSTRAINT chk_pet_health_record_dose CHECK (
        dose_number IS NULL OR (dose_number >= 1 AND dose_number <= 20)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 健康数据写审计（不含健康内容明文：仅动作/对象/请求元数据，镜像 35号 staff audit 风格）。
-- 同事务写入：幂等锁 → 记录插入 → 审计插入 → 幂等回执，任一失败整体回滚。
CREATE TABLE user_pet_health_record_audit (
    id            BIGINT        NOT NULL,
    actor_id      BIGINT        NOT NULL COMMENT '操作用户',
    action_code   VARCHAR(48)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                  COMMENT 'pet.health-record.create / pet.health-record.disable',
    record_id     BIGINT        NOT NULL,
    pet_id        BIGINT        NOT NULL,
    record_type   VARCHAR(16)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key   VARBINARY(1024) NOT NULL COMMENT '幂等 requestKey（23号 §5 同源）',
    request_id    VARBINARY(512)  NOT NULL,
    trace_id      VARCHAR(128)  NULL,
    occurred_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pet_health_record_audit_request (request_key),
    KEY idx_pet_health_record_audit_record (record_id, occurred_at),
    CONSTRAINT chk_pet_health_record_audit_ids CHECK (id>0 AND actor_id>0 AND record_id>0 AND pet_id>0),
    CONSTRAINT chk_pet_health_record_audit_action CHECK (
        action_code IN ('pet.health-record.create','pet.health-record.disable')
    ),
    CONSTRAINT chk_pet_health_record_audit_type CHECK (record_type IN ('VACCINATION','DEWORMING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
