-- 宠物平台 V1.0 核心数据库设计 v0.1
-- Target: MySQL 8.0 / InnoDB / utf8mb4
-- 设计原则：模块化单体、逻辑外键、未来微服务可拆分、交易数据不可物理删除。
-- 产品 SSOT 冲突时，以产品 SSOT 为准。

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ============================================================
-- 0. 用户与基础身份
-- ============================================================

CREATE TABLE user_account (
    id                    BIGINT       NOT NULL COMMENT 'Snowflake PK',
    phone                 VARCHAR(32)  NULL COMMENT '绑定手机号，唯一；允许初始微信身份先创建后补齐',
    nickname              VARCHAR(64)  NULL,
    avatar_url            VARCHAR(512) NULL,
    password_hash         VARCHAR(255) NULL COMMENT '未设置密码时为空，不生成随机密码',
    password_enabled      TINYINT(1)   NOT NULL DEFAULT 0,
    status                VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN/CANCELED',
    last_login_at         DATETIME(3)  NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone),
    KEY idx_user_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_auth_identity (
    id                    BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    identity_type         VARCHAR(32)  NOT NULL COMMENT 'WECHAT_MINI / future APP provider',
    app_id                VARCHAR(128) NULL,
    open_id               VARCHAR(128) NULL,
    union_id              VARCHAR(128) NULL,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_identity (identity_type, app_id, open_id),
    KEY idx_auth_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_pet (
    id                    BIGINT        NOT NULL,
    user_id               BIGINT        NOT NULL,
    name                  VARCHAR(64)   NOT NULL,
    pet_type              VARCHAR(32)   NOT NULL,
    breed_name            VARCHAR(64)   NULL,
    birth_date            DATE          NULL,
    sex                   VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN',
    weight_kg             DECIMAL(8,2)  NULL,
    sterilization_status  VARCHAR(32)   NULL,
    vaccine_status        VARCHAR(32)   NULL,
    health_note           VARCHAR(1000) NULL,
    avatar_url            VARCHAR(512)  NULL COMMENT '宠物头像URL,可空',
    is_default            TINYINT(1)    NOT NULL DEFAULT 0,
    status                VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED(软删除,历史order_pet_snapshot不受影响)',
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            DATETIME(3)   NOT NULL,
    updated_at            DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_pet_user_status (user_id, status),
    KEY idx_pet_user_default (user_id, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 1. 商家 / 门店 / 服务 / 服务人员
-- ============================================================

CREATE TABLE merchant (
    id                    BIGINT       NOT NULL,
    owner_user_id         BIGINT       NOT NULL COMMENT '拥有商家身份的平台用户',
    merchant_name         VARCHAR(128) NOT NULL,
    status                VARCHAR(32)  NOT NULL COMMENT 'APPLYING/ACTIVE/OFFLINE/FROZEN/CANCELED',
    provider_merchant_no  VARCHAR(128) NULL COMMENT '支付服务商签约标识',
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_owner (owner_user_id),
    KEY idx_merchant_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_store (
    id                    BIGINT       NOT NULL,
    merchant_id           BIGINT       NOT NULL,
    store_name            VARCHAR(128) NOT NULL,
    address               VARCHAR(255) NOT NULL,
    longitude             DECIMAL(10,7) NULL,
    latitude              DECIMAL(10,7) NULL,
    phone                 VARCHAR(32)  NULL,
    status                VARCHAR(32)  NOT NULL COMMENT 'ACTIVE/OFFLINE/FROZEN',
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_store_merchant_status (merchant_id, status),
    KEY idx_store_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE merchant_staff (
    id                    BIGINT       NOT NULL,
    merchant_id           BIGINT       NOT NULL,
    store_id              BIGINT       NOT NULL,
    staff_name            VARCHAR(64)  NOT NULL,
    phone                 VARCHAR(32)  NULL,
    employment_status     VARCHAR(32)  NOT NULL COMMENT 'ACTIVE/INACTIVE',
    service_enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_staff_store_status (store_id, employment_status),
    KEY idx_staff_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_category (
    id                    BIGINT       NOT NULL,
    category_name         VARCHAR(64)  NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    sort_no               INT          NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_category_name (category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_item (
    id                    BIGINT         NOT NULL,
    merchant_id           BIGINT         NOT NULL,
    store_id              BIGINT         NOT NULL,
    category_id           BIGINT         NOT NULL,
    service_name          VARCHAR(128)   NOT NULL,
    description           TEXT           NULL,
    price                 DECIMAL(18,2)  NOT NULL,
    duration_minutes      INT            NOT NULL COMMENT '单次服务时长，用于分钟级排期',
    fulfillment_type      VARCHAR(32)    NOT NULL COMMENT 'IN_STORE/PICKUP_DELIVERY',
    status                VARCHAR(32)    NOT NULL COMMENT 'DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED（33号扩展：C端可见性仅ACTIVE；编辑仅DRAFT/REJECTED/OFFLINE；列扩展见33号）',
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_service_store_status (store_id, status),
    KEY idx_service_merchant_status (merchant_id, status),
    KEY idx_service_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE staff_service_capability (
    id                    BIGINT       NOT NULL,
    staff_id              BIGINT       NOT NULL,
    service_id            BIGINT       NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staff_service (staff_id, service_id),
    KEY idx_capability_service_status (service_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 2. 分钟级排期
-- ============================================================

CREATE TABLE schedule_availability_window (
    id                    BIGINT       NOT NULL,
    merchant_id           BIGINT       NOT NULL,
    store_id              BIGINT       NOT NULL,
    service_id            BIGINT       NOT NULL,
    start_at              DATETIME(3)  NOT NULL,
    end_at                DATETIME(3)  NOT NULL,
    configured_capacity   INT          NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_schedule_service_time (store_id, service_id, start_at, end_at),
    KEY idx_schedule_status_time (status, start_at, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE staff_availability_window (
    id                    BIGINT       NOT NULL,
    store_id              BIGINT       NOT NULL,
    staff_id              BIGINT       NOT NULL,
    start_at              DATETIME(3)  NOT NULL,
    end_at                DATETIME(3)  NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/CLOSED',
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_staff_avail_time (staff_id, status, start_at, end_at),
    KEY idx_store_avail_time (store_id, status, start_at, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE schedule_reservation (
    id                              BIGINT       NOT NULL,
    order_id                        BIGINT       NOT NULL,
    merchant_id                     BIGINT       NOT NULL,
    store_id                        BIGINT       NOT NULL,
    service_id                      BIGINT       NOT NULL,
    fulfillment_type                VARCHAR(32)  NOT NULL,
    start_at                        DATETIME(3)  NOT NULL,
    end_at                          DATETIME(3)  NOT NULL,
    pickup_start_at                 DATETIME(3)  NULL,
    return_start_at                 DATETIME(3)  NULL,
    status                          VARCHAR(32)  NOT NULL COMMENT 'TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED',
    lock_token                      VARCHAR(64)  NULL,
    lock_expire_at                  DATETIME(3)  NULL,
    capacity_snapshot               INT          NOT NULL,
    qualified_staff_count_snapshot  INT          NOT NULL,
    version                         BIGINT       NOT NULL DEFAULT 0,
    created_at                      DATETIME(3)  NOT NULL,
    updated_at                      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_order (order_id),
    UNIQUE KEY uk_reservation_lock_token (lock_token),
    KEY idx_reservation_service_time (store_id, service_id, start_at, end_at, status),
    KEY idx_reservation_expire (status, lock_expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 3. 订单核心
-- ============================================================

CREATE TABLE pet_order (
    id                              BIGINT         NOT NULL,
    order_no                        BIGINT         NOT NULL COMMENT 'Snowflake，对外 API 作为字符串',
    user_id                         BIGINT         NOT NULL,
    merchant_id                     BIGINT         NOT NULL,
    store_id                        BIGINT         NOT NULL,
    service_id                      BIGINT         NOT NULL,
    pet_id                          BIGINT         NOT NULL,
    reservation_id                  BIGINT         NOT NULL,
    service_staff_id                BIGINT         NULL COMMENT '商家最终指派的服务人员',
    order_stage                     VARCHAR(32)    NOT NULL COMMENT 'PENDING_PAYMENT/PENDING_CONFIRM/PENDING_SERVICE/COMPLETED/CANCELED',
    payment_status                  VARCHAR(32)    NOT NULL DEFAULT 'INIT',
    verification_status             VARCHAR(32)    NOT NULL DEFAULT 'UNVERIFIED',
    current_refund_application_id   BIGINT         NULL,
    refund_order_id                 BIGINT         NULL,
    current_aftersale_id            BIGINT         NULL,
    fulfillment_type                VARCHAR(32)    NOT NULL COMMENT 'IN_STORE/PICKUP_DELIVERY',
    original_amount                 DECIMAL(18,2)  NOT NULL,
    discount_amount                 DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    pay_amount                      DECIMAL(18,2)  NOT NULL,
    refunded_amount                 DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    reschedule_count                INT            NOT NULL DEFAULT 0,
    confirm_mode                    VARCHAR(16)    NULL COMMENT 'MERCHANT/AUTO',
    confirm_deadline                DATETIME(3)    NULL,
    appointment_start_at            DATETIME(3)    NOT NULL,
    appointment_end_at              DATETIME(3)    NOT NULL,
    paid_at                         DATETIME(3)    NULL,
    confirmed_at                    DATETIME(3)    NULL,
    verified_at                     DATETIME(3)    NULL,
    completed_at                    DATETIME(3)    NULL,
    canceled_at                     DATETIME(3)    NULL,
    version                         BIGINT         NOT NULL DEFAULT 0,
    created_at                      DATETIME(3)    NOT NULL,
    updated_at                      DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_order_reservation (reservation_id),
    UNIQUE KEY uk_order_refund_order (refund_order_id),
    KEY idx_order_user_created (user_id, created_at),
    KEY idx_order_store_stage_created (store_id, order_stage, created_at),
    KEY idx_order_merchant_stage_created (merchant_id, order_stage, created_at),
    KEY idx_order_confirm_deadline (order_stage, confirm_deadline),
    KEY idx_order_current_aftersale (current_aftersale_id),
    KEY idx_order_service_time (service_id, appointment_start_at, appointment_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_service_snapshot (
    id                    BIGINT         NOT NULL,
    order_id              BIGINT         NOT NULL,
    service_id            BIGINT         NOT NULL,
    service_name          VARCHAR(128)   NOT NULL,
    category_id           BIGINT         NULL,
    category_name         VARCHAR(64)    NULL,
    service_price         DECIMAL(18,2)  NOT NULL,
    service_duration_minutes INT         NOT NULL,
    service_description   TEXT           NULL,
    merchant_name         VARCHAR(128)   NOT NULL,
    store_name            VARCHAR(128)   NOT NULL,
    store_address         VARCHAR(255)   NOT NULL,
    snapshot_json         JSON           NULL,
    created_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_service_snapshot (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_pet_snapshot (
    id                    BIGINT         NOT NULL,
    order_id              BIGINT         NOT NULL,
    pet_id                BIGINT         NOT NULL,
    pet_name              VARCHAR(64)    NOT NULL,
    pet_type              VARCHAR(32)    NOT NULL,
    breed_name            VARCHAR(64)    NULL,
    sex                   VARCHAR(16)    NULL,
    weight_kg             DECIMAL(8,2)   NULL,
    health_note           VARCHAR(1000)  NULL,
    created_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_pet_snapshot (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_status_log (
    id                    BIGINT       NOT NULL,
    order_id              BIGINT       NOT NULL,
    dimension             VARCHAR(32)  NOT NULL COMMENT 'ORDER_STAGE/PAYMENT/REFUND_APPLICATION/REFUND/AFTERSALE/VERIFICATION',
    from_status           VARCHAR(32)  NULL,
    to_status             VARCHAR(32)  NOT NULL,
    event_type            VARCHAR(64)  NOT NULL,
    operator_type         VARCHAR(32)  NOT NULL COMMENT 'USER/MERCHANT/OPS/SYSTEM/CHANNEL',
    operator_id           BIGINT       NULL,
    request_id            VARCHAR(64)  NULL,
    remark                VARCHAR(1000) NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_status_log (order_id, created_at),
    KEY idx_order_status_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_operation_guard (
    id                    BIGINT       NOT NULL,
    order_id              BIGINT       NOT NULL,
    operation_type        VARCHAR(32)  NOT NULL COMMENT 'VERIFY/CREATE_REFUND',
    token                 VARCHAR(64)  NOT NULL,
    status                VARCHAR(16)  NOT NULL COMMENT 'ACQUIRED/COMMITTED/RELEASED/EXPIRED',
    expire_at             DATETIME(3)  NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_guard_token (token),
    KEY idx_order_guard_order_status (order_id, status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_staff_assignment (
    id                    BIGINT       NOT NULL,
    order_id              BIGINT       NOT NULL,
    staff_id              BIGINT       NOT NULL,
    assigned_by_type      VARCHAR(32)  NOT NULL COMMENT 'MERCHANT/SYSTEM_ADMIN',
    assigned_by_id        BIGINT       NULL,
    is_current            TINYINT(1)   NOT NULL DEFAULT 1,
    assigned_at           DATETIME(3)  NOT NULL,
    removed_at            DATETIME(3)  NULL,
    current_order_guard   BIGINT GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN order_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_current_staff (current_order_guard),
    KEY idx_assignment_staff (staff_id, is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 4. 支付
-- ============================================================

CREATE TABLE payment_order (
    id                    BIGINT         NOT NULL,
    payment_no            BIGINT         NOT NULL,
    order_id              BIGINT         NOT NULL,
    amount                DECIMAL(18,2)  NOT NULL,
    status                VARCHAR(32)    NOT NULL COMMENT 'INIT/PAYING/PAID/FAILED/CLOSED',
    channel               VARCHAR(32)    NOT NULL COMMENT 'LAKALA_WECHAT; future LAKALA_ALIPAY',
    expire_at             DATETIME(3)    NOT NULL,
    paid_at               DATETIME(3)    NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    UNIQUE KEY uk_payment_order (order_id),
    KEY idx_payment_status_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_transaction (
    id                    BIGINT       NOT NULL,
    payment_id            BIGINT       NOT NULL,
    request_id            VARCHAR(64)  NOT NULL,
    action                VARCHAR(32)  NOT NULL COMMENT 'CREATE/QUERY/CALLBACK',
    channel_trade_no      VARCHAR(128) NULL,
    channel_status        VARCHAR(64)  NULL,
    raw_payload           JSON         NULL COMMENT '只保存必要且脱敏的数据',
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_request (payment_id, request_id, action),
    KEY idx_payment_channel_trade (channel_trade_no),
    KEY idx_payment_txn_created (payment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 5. 退款：申请与实际退款分离
-- ============================================================

CREATE TABLE refund_application (
    id                        BIGINT         NOT NULL,
    application_no            BIGINT         NOT NULL,
    order_id                  BIGINT         NOT NULL,
    applicant_user_id         BIGINT         NOT NULL,
    status                    VARCHAR(32)    NOT NULL COMMENT 'PENDING_MERCHANT/APPROVED/AUTO_APPROVED/REJECTED',
    reason_code               VARCHAR(64)    NULL,
    reason_text               VARCHAR(500)   NULL,
    requested_amount          DECIMAL(18,2)  NOT NULL,
    merchant_decision_reason  VARCHAR(500)   NULL COMMENT '拒绝时必填，展示买家',
    merchant_deadline         DATETIME(3)    NULL COMMENT '服务开始后申请时 +24h',
    decided_by                BIGINT         NULL,
    decided_at                DATETIME(3)    NULL,
    request_id                VARCHAR(64)    NOT NULL,
    created_at                DATETIME(3)    NOT NULL,
    updated_at                DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_application_no (application_no),
    UNIQUE KEY uk_refund_application_request (request_id),
    KEY idx_refund_application_order (order_id, created_at),
    KEY idx_refund_application_deadline (status, merchant_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refund_order (
    id                        BIGINT         NOT NULL,
    refund_no                 BIGINT         NOT NULL COMMENT 'Snowflake，对外 API 作为字符串',
    order_id                  BIGINT         NOT NULL,
    refund_application_id     BIGINT         NULL,
    aftersale_id              BIGINT         NULL,
    refund_type               VARCHAR(16)    NOT NULL COMMENT 'FULL/PARTIAL',
    source_type               VARCHAR(32)    NOT NULL COMMENT 'PRESTART_AUTO/MERCHANT_REJECT_ORDER/MERCHANT_APPROVED/AFTERSALE/OPS_EXCEPTION',
    refund_amount             DECIMAL(18,2)  NOT NULL,
    refund_ratio              DECIMAL(10,6)  NOT NULL,
    status                    VARCHAR(32)    NOT NULL COMMENT 'CREATED/PROCESSING/SUCCESS/FAILED/UNKNOWN',
    initiator_type            VARCHAR(32)    NOT NULL COMMENT 'SYSTEM/MERCHANT/OPS',
    initiator_id              BIGINT         NULL,
    channel                   VARCHAR(32)    NOT NULL DEFAULT 'LAKALA',
    channel_refund_no         VARCHAR(128)   NULL,
    succeeded_at              DATETIME(3)    NULL,
    version                   BIGINT         NOT NULL DEFAULT 0,
    created_at                DATETIME(3)    NOT NULL COMMENT '创建成功即形成永久禁止核销边界',
    updated_at                DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_refund_order_once (order_id),
    KEY idx_refund_status_created (status, created_at),
    KEY idx_refund_aftersale (aftersale_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refund_transaction (
    id                        BIGINT       NOT NULL,
    refund_id                 BIGINT       NOT NULL,
    request_id                VARCHAR(64)  NOT NULL,
    action                    VARCHAR(32)  NOT NULL COMMENT 'REFUND/QUERY/CALLBACK',
    channel_request_no        VARCHAR(128) NULL,
    channel_status            VARCHAR(64)  NULL,
    raw_payload               JSON         NULL COMMENT '必要且脱敏',
    created_at                DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_request (refund_id, request_id, action),
    KEY idx_refund_channel_req (channel_request_no),
    KEY idx_refund_txn_created (refund_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 6. 核销
-- ============================================================

CREATE TABLE verification_record (
    id                    BIGINT       NOT NULL,
    verification_no       BIGINT       NOT NULL,
    order_id              BIGINT       NOT NULL,
    store_id              BIGINT       NOT NULL,
    operator_staff_id     BIGINT       NOT NULL,
    verify_method         VARCHAR(16)  NOT NULL COMMENT 'SCAN/MANUAL',
    request_id            VARCHAR(64)  NOT NULL,
    verified_at           DATETIME(3)  NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_no (verification_no),
    UNIQUE KEY uk_verification_order (order_id),
    UNIQUE KEY uk_verification_request (request_id),
    KEY idx_verification_store_time (store_id, verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE verification_attempt (
    id                    BIGINT       NOT NULL,
    order_id              BIGINT       NULL,
    store_id              BIGINT       NOT NULL,
    operator_staff_id     BIGINT       NOT NULL,
    request_id            VARCHAR(64)  NOT NULL,
    result                VARCHAR(32)  NOT NULL COMMENT 'SUCCESS/FAILED/LOCKED/REJECTED',
    fail_reason           VARCHAR(128) NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_attempt_request (request_id),
    KEY idx_verify_attempt_order_time (order_id, created_at),
    KEY idx_verify_attempt_operator_time (operator_staff_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 7. 售后
-- ============================================================

CREATE TABLE aftersale_case (
    id                    BIGINT         NOT NULL,
    aftersale_no          BIGINT         NOT NULL,
    order_id              BIGINT         NOT NULL,
    user_id               BIGINT         NOT NULL,
    merchant_id           BIGINT         NOT NULL,
    store_id              BIGINT         NOT NULL,
    status                VARCHAR(32)    NOT NULL COMMENT 'PENDING/PROCESSING/WAITING_SUPPLEMENT/RESOLVED/INVALIDATED/WITHDRAWN/CLOSED',
    source_stage          VARCHAR(32)    NOT NULL COMMENT 'UNVERIFIED_POST_START/VERIFIED',
    reason_code           VARCHAR(64)    NULL,
    description           VARCHAR(2000)  NOT NULL,
    requested_amount      DECIMAL(18,2)  NULL,
    decision_type         VARCHAR(32)    NULL COMMENT 'FULL_REFUND/PARTIAL_REFUND/REJECT/RESERVICE/OTHER_NON_REFUND',
    decision_amount       DECIMAL(18,2)  NULL,
    decision_reason       VARCHAR(1000)  NULL,
    active_flag           TINYINT(1)     NOT NULL DEFAULT 1,
    created_at            DATETIME(3)    NOT NULL,
    accepted_at           DATETIME(3)    NULL,
    resolved_at           DATETIME(3)    NULL,
    invalidated_at        DATETIME(3)    NULL,
    closed_at             DATETIME(3)    NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    active_order_guard    BIGINT GENERATED ALWAYS AS (CASE WHEN active_flag = 1 THEN order_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_aftersale_no (aftersale_no),
    UNIQUE KEY uk_aftersale_one_active (active_order_guard),
    KEY idx_aftersale_order_created (order_id, created_at),
    KEY idx_aftersale_status_created (status, created_at),
    KEY idx_aftersale_store_status (store_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE aftersale_evidence (
    id                    BIGINT       NOT NULL,
    aftersale_id          BIGINT       NOT NULL,
    submitter_type        VARCHAR(32)  NOT NULL COMMENT 'USER/MERCHANT/OPS',
    submitter_id          BIGINT       NULL,
    evidence_type         VARCHAR(32)  NOT NULL COMMENT 'TEXT/IMAGE/FILE',
    content               VARCHAR(2000) NULL,
    file_url              VARCHAR(512) NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_aftersale_evidence (aftersale_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE aftersale_status_log (
    id                    BIGINT        NOT NULL,
    aftersale_id          BIGINT        NOT NULL,
    from_status           VARCHAR(32)   NULL,
    to_status             VARCHAR(32)   NOT NULL,
    event_type            VARCHAR(64)   NOT NULL,
    operator_type         VARCHAR(32)   NOT NULL,
    operator_id           BIGINT        NULL,
    remark                VARCHAR(1000) NULL,
    created_at            DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_aftersale_log (aftersale_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 8. 优惠券
-- ============================================================

CREATE TABLE coupon_template (
    id                    BIGINT         NOT NULL,
    name                  VARCHAR(128)   NOT NULL,
    status                VARCHAR(16)    NOT NULL COMMENT 'DRAFT/ACTIVE/OFFLINE',
    total_stock           BIGINT         NULL,
    issued_count          BIGINT         NOT NULL DEFAULT 0,
    valid_start_at        DATETIME(3)    NOT NULL,
    valid_end_at          DATETIME(3)    NOT NULL,
    rule_json             JSON           NOT NULL COMMENT '门槛、适用商家/服务、优惠计算等配置',
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coupon_template_status_time (status, valid_start_at, valid_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coupon_instance (
    id                    BIGINT       NOT NULL,
    coupon_template_id    BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    status                VARCHAR(32)  NOT NULL COMMENT 'AVAILABLE/FROZEN/USED/EXPIRED/RISK_FROZEN',
    order_id              BIGINT       NULL,
    frozen_at             DATETIME(3)  NULL,
    freeze_expire_at      DATETIME(3)  NULL,
    used_at               DATETIME(3)  NULL,
    original_expire_at    DATETIME(3)  NOT NULL,
    expire_at             DATETIME(3)  NOT NULL COMMENT '全额退款时可能延长到退款成功+24h',
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coupon_user_status_expire (user_id, status, expire_at),
    KEY idx_coupon_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coupon_ledger (
    id                    BIGINT       NOT NULL,
    coupon_instance_id    BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    order_id              BIGINT       NULL,
    action                VARCHAR(32)  NOT NULL COMMENT 'ISSUE/FREEZE/CONSUME/RELEASE/REFUND_RESTORE/EXPIRE/RISK_FREEZE',
    from_status           VARCHAR(32)  NULL,
    to_status             VARCHAR(32)  NOT NULL,
    request_id            VARCHAR(64)  NOT NULL,
    remark                VARCHAR(500) NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_ledger_request (request_id),
    KEY idx_coupon_ledger_instance (coupon_instance_id, created_at),
    KEY idx_coupon_ledger_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 9. 积分：V1.0 仅赚取与退款扣回
-- ============================================================

CREATE TABLE points_account (
    id                    BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    balance               BIGINT       NOT NULL DEFAULT 0,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE points_ledger (
    id                    BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    biz_type              VARCHAR(32)  NOT NULL COMMENT 'SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK',
    biz_id                BIGINT       NULL,
    order_id              BIGINT       NULL,
    delta                 BIGINT       NOT NULL COMMENT '增加为正，扣回为负',
    balance_after         BIGINT       NOT NULL,
    request_id            VARCHAR(64)  NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_request (request_id),
    KEY idx_points_user_time (user_id, created_at),
    KEY idx_points_order_type (order_id, biz_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 10. 评价
-- ============================================================

CREATE TABLE review (
    id                    BIGINT         NOT NULL,
    order_id              BIGINT         NOT NULL,
    user_id               BIGINT         NOT NULL,
    merchant_id           BIGINT         NOT NULL,
    store_id              BIGINT         NOT NULL,
    service_id            BIGINT         NOT NULL,
    staff_id              BIGINT         NULL,
    store_score           DECIMAL(2,1)   NOT NULL,
    service_score         DECIMAL(2,1)   NOT NULL,
    staff_score           DECIMAL(2,1)   NOT NULL,
    composite_score       DECIMAL(3,1)   NOT NULL COMMENT '门店40%+服务40%+人员20%',
    score_included        TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '已核销后部分退款置0',
    content               VARCHAR(2000)  NULL,
    visibility_status     VARCHAR(32)    NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/HIDDEN',
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_order (order_id),
    KEY idx_review_store_score (store_id, score_included, created_at),
    KEY idx_review_service_score (service_id, score_included, created_at),
    KEY idx_review_staff_score (staff_id, score_included, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_media (
    id                    BIGINT       NOT NULL,
    review_id             BIGINT       NOT NULL,
    media_type            VARCHAR(16)  NOT NULL COMMENT 'IMAGE',
    media_url             VARCHAR(512) NOT NULL,
    sort_no               INT          NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_review_media (review_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_appeal (
    id                    BIGINT        NOT NULL,
    review_id             BIGINT        NOT NULL,
    merchant_id           BIGINT        NOT NULL,
    status                VARCHAR(32)   NOT NULL COMMENT 'SUBMITTED/PROCESSING/APPROVED/REJECTED',
    reason                VARCHAR(1000) NOT NULL,
    decision_reason       VARCHAR(1000) NULL,
    decided_by            BIGINT        NULL,
    decided_at            DATETIME(3)   NULL,
    created_at            DATETIME(3)   NOT NULL,
    updated_at            DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_appeal_once (review_id),
    KEY idx_review_appeal_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 11. 通知
-- ============================================================

CREATE TABLE notification (
    id                    BIGINT        NOT NULL,
    receiver_type         VARCHAR(16)   NOT NULL COMMENT 'USER/MERCHANT/OPS',
    receiver_id           BIGINT        NOT NULL,
    category              VARCHAR(32)   NOT NULL COMMENT 'INTERACTION/SERVICE/SYSTEM',
    message_type          VARCHAR(64)   NOT NULL,
    biz_type              VARCHAR(32)   NULL,
    biz_id                BIGINT        NULL,
    title                 VARCHAR(128)  NOT NULL,
    content               VARCHAR(1000) NOT NULL,
    mandatory_inbox       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '订单/退款/核销/售后/审核为1',
    read_at               DATETIME(3)   NULL,
    created_at            DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_receiver (receiver_type, receiver_id, created_at),
    KEY idx_notification_receiver_unread (receiver_type, receiver_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_preference (
    id                    BIGINT       NOT NULL,
    receiver_type         VARCHAR(16)  NOT NULL,
    receiver_id           BIGINT       NOT NULL,
    interaction_enabled   TINYINT(1)   NOT NULL DEFAULT 1,
    external_push_enabled TINYINT(1)   NOT NULL DEFAULT 1,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_pref_receiver (receiver_type, receiver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_delivery (
    id                    BIGINT       NOT NULL,
    notification_id       BIGINT       NOT NULL,
    channel               VARCHAR(32)  NOT NULL COMMENT 'INBOX/WECHAT_SUBSCRIBE/WECHAT_OA',
    status                VARCHAR(32)  NOT NULL COMMENT 'PENDING/SENT/FAILED/SKIPPED',
    provider_message_id   VARCHAR(128) NULL,
    retry_count           INT          NOT NULL DEFAULT 0,
    last_error            VARCHAR(500) NULL,
    sent_at               DATETIME(3)  NULL,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_channel (notification_id, channel),
    KEY idx_delivery_status_retry (status, retry_count, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 12. Transactional Outbox / Event Idempotency
-- ============================================================

CREATE TABLE integration_event_outbox (
    id                    BIGINT       NOT NULL,
    event_id              VARCHAR(64)  NOT NULL,
    aggregate_type        VARCHAR(64)  NOT NULL,
    aggregate_id          BIGINT       NOT NULL,
    event_type            VARCHAR(128) NOT NULL,
    event_version         INT          NOT NULL DEFAULT 1,
    payload               JSON         NOT NULL,
    occurred_at           DATETIME(3)  NOT NULL COMMENT '业务事实发生时间(信封occurredAt),重发保持原值',
    status                VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/PUBLISHING/PUBLISHED/FAILED',
    retry_count           INT          NOT NULL DEFAULT 0,
    next_retry_at         DATETIME(3)  NULL,
    lease_owner           VARCHAR(128) NULL COMMENT '发布租约归属,对齐async_task约定',
    lease_until           DATETIME(3)  NULL COMMENT '租约期限,过期允许其他Worker接管(Scheduler §22)',
    trace_id              VARCHAR(64)  NULL,
    created_at            DATETIME(3)  NOT NULL,
    published_at          DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_id),
    KEY idx_outbox_publish (status, next_retry_at, created_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id, created_at),
    KEY idx_outbox_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE integration_event_consume_log (
    id                    BIGINT       NOT NULL,
    consumer_name         VARCHAR(128) NOT NULL,
    event_id              VARCHAR(64)  NOT NULL,
    event_type            VARCHAR(128) NOT NULL,
    consumed_at           DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumer_event (consumer_name, event_id),
    KEY idx_consume_log_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 13. 设计说明（非 SQL 约束）
-- ============================================================
-- 1) 不建立跨模块物理 FOREIGN KEY；所有跨模块 ID 为逻辑引用。
-- 2) 模块内部数据一致性由事务、唯一键、乐观锁保证。
-- 3) refund_order UNIQUE(order_id)：一笔订单最多一张业务退款单；渠道重试写 refund_transaction。
-- 4) verification_record UNIQUE(order_id)：一笔订单最多一次成功核销。
-- 5) review UNIQUE(order_id)：一笔订单最多一次评价。
-- 6) review_appeal UNIQUE(review_id)：一条评价最多一次申诉。
-- 7) 退款申请本身不释放预约；RefundSucceededEvent 到达 schedule 模块后才释放。
-- 8) 售后处理中不自动禁止核销；只有实际 refund_order 创建成功后永久禁止核销。
-- 9) 已核销部分退款：已有评价需将 score_included 更新为 0；后续新评价创建时直接为 0。
-- 10) 未核销部分退款：ReviewEligibility 直接返回 false。
-- 11) 分钟级排期区间统一采用 [start_at, end_at) 半开区间，重叠判定：existing.start < requested.end AND existing.end > requested.start。
-- 12) PICKUP_DELIVERY 必须校验 return_start_at >= pickup_start_at + 120 MINUTE。
-- 13) 退款 × 核销最终互斥由 OrderOperationGuardApi + order_operation_guard 实现；临时 guard 仅表示“处理中”，commit refund guard 后才是正式退款创建边界。
