-- ============================================================
-- 55 - 用户地址簿 Schema v0.1（32号裁决补充第二节：地址管理页）
-- 2026-10-13。归属：USER 域（pet-user-biz）。
-- 结构镜像 user_pet（SQL06 §0）：Snowflake BIGINT 主键、软删除 status、
-- 每用户默认地址唯一（idx_addr_user_default 支撑 clear-default 语句）、
-- 单用户 ACTIVE 地址上限 20（应用层计数校验，见 59号契约 §3）。
-- 订单不引用本表：下单仍走 order_booking_input_snapshot 文本快照
-- （service_address_ciphertext），地址簿仅为 C 端输入源，删除地址不影响存量订单。
-- ============================================================

CREATE TABLE user_address (
    id            BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    contact_name  VARCHAR(32)   NOT NULL COMMENT '收货人姓名,1~32码点',
    contact_phone VARCHAR(16)   NOT NULL COMMENT '收货人手机号,大陆11位',
    region        VARCHAR(64)   NOT NULL COMMENT '所在地区(省/市/区),1~64码点',
    detail        VARCHAR(256)  NOT NULL COMMENT '详细地址,1~256码点',
    is_default    TINYINT(1)    NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED(软删除,订单快照不受影响)',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_addr_user_status (user_id, status),
    KEY idx_addr_user_default (user_id, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
