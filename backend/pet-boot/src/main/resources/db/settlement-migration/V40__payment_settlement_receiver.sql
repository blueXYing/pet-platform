-- CCR-PAY-CENTER-001 阶段 4：分账接收方台账与敏感查看/操作不可变审计。
-- 契约：docs/04-api/80-Payment-Settlement-Full-API-Contract-v0.1.md §1 / §4.2 / §5；
-- 裁决：docs/01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md §4 / §5.4。
-- 与 backend/pet-boot/src/main/resources/db/settlement-migration/V40__payment_settlement_receiver.sql 保持一字不差。

CREATE TABLE settlement_receiver_account (
    id                      BIGINT         NOT NULL PRIMARY KEY,
    merchant_id             BIGINT         NOT NULL COMMENT '商家 ID（每商家唯一分账接收方）',
    receiver_name           VARCHAR(64)    NOT NULL COMMENT '分账接收方名称',
    receiver_type           VARCHAR(16)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PERSONAL / ENTERPRISE',
    acct_name               VARCHAR(64)    NOT NULL COMMENT '收款账户名称',
    acct_type_code          VARCHAR(16)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '57 对公 / 58 对私',
    acct_certificate_type   VARCHAR(16)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收款账户证件类型（17 身份证等）',
    acct_open_bank_code     VARCHAR(32)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收款账户开户行号',
    acct_open_bank_name     VARCHAR(64)    NOT NULL COMMENT '收款账户开户行名称',
    acct_clear_bank_code    VARCHAR(32)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收款账户清算行行号',
    license_name            VARCHAR(128)   NULL COMMENT '营业执照名称（对公必传）',
    legal_person_name       VARCHAR(64)    NULL COMMENT '法人姓名（对公必传）',
    legal_person_cert_type  VARCHAR(16)    CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '法人证件类型（对公必传）',
    acct_no_cipher          VARCHAR(512)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'AES-256-GCM 密文：收款账户卡号',
    acct_cert_no_cipher     VARCHAR(512)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'AES-256-GCM 密文：收款账户证件号',
    contact_mobile_cipher   VARCHAR(512)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'AES-256-GCM 密文：联系手机号',
    license_no_cipher       VARCHAR(512)   CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'AES-256-GCM 密文：营业执照号码',
    legal_cert_no_cipher    VARCHAR(512)   CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'AES-256-GCM 密文：法人证件号',
    acct_no_masked          VARCHAR(64)    NOT NULL COMMENT '脱敏收款账户卡号',
    acct_no_last4           CHAR(4)        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收款账户卡号后 4 位',
    acct_cert_no_masked     VARCHAR(64)    NOT NULL COMMENT '脱敏收款账户证件号',
    contact_mobile_masked   VARCHAR(32)    NOT NULL COMMENT '脱敏联系手机号',
    license_no_masked       VARCHAR(64)    NULL COMMENT '脱敏营业执照号码',
    legal_cert_no_masked    VARCHAR(64)    NULL COMMENT '脱敏法人证件号',
    status                  VARCHAR(32)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DRAFT/RECEIVER_APPLYING/RECEIVER_CREATED/BIND_APPLYING/ACTIVE/REJECTED/CHANGE_APPLYING',
    receiver_no             VARCHAR(32)    CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '拉卡拉分账接收方编号（1287 返回）',
    apply_id                VARCHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '拉卡拉分账关系绑定受理编号（1290 返回）',
    settle_type             VARCHAR(8)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '01' COMMENT '提款模式，固定 01 主动提款',
    attachments_json        TEXT           NULL COMMENT '仅存平台私有素材 ID 列表 JSON，严禁长期保存拉卡拉 24h attFileId',
    cooperation_asset_id    VARCHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '分账合作协议私有素材 ID',
    reject_reason           VARCHAR(512)   NULL COMMENT '拉卡拉审核驳回原因',
    last_channel_order_no   VARCHAR(32)    CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近一次拉卡拉请求 orderNo',
    last_synced_at          DATETIME(3)    NULL COMMENT '最近一次拉卡拉同步或回调时间（UTC）',
    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              DATETIME(3)    NOT NULL,
    updated_at              DATETIME(3)    NOT NULL,
    UNIQUE KEY uk_receiver_merchant (merchant_id),
    UNIQUE KEY uk_receiver_no (receiver_no),
    KEY idx_receiver_status (status, updated_at),
    KEY idx_receiver_apply_id (apply_id),
    CONSTRAINT ck_receiver_type CHECK (receiver_type IN ('PERSONAL', 'ENTERPRISE')),
    CONSTRAINT ck_receiver_acct_type CHECK (acct_type_code IN ('57', '58')),
    CONSTRAINT ck_receiver_settle_type CHECK (settle_type = '01'),
    CONSTRAINT ck_receiver_status CHECK (status IN (
        'DRAFT', 'RECEIVER_APPLYING', 'RECEIVER_CREATED', 'BIND_APPLYING',
        'ACTIVE', 'REJECTED', 'CHANGE_APPLYING'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_receiver_audit (
    id                   BIGINT          NOT NULL PRIMARY KEY,
    merchant_id          BIGINT          NOT NULL,
    receiver_account_id  BIGINT          NOT NULL,
    action_type          VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DRAFT_SAVE/CREATE/CREATE_REJECTED/MODIFY/MODIFY_REJECTED/BIND/BIND_REJECTED/SYNC/REVEAL/BIND_NOTIFY/BIND_POLL',
    request_key          VARBINARY(1024) NULL COMMENT '写操作幂等键：settlement.receiver.<action>/{operatorType}/{operatorId}/{requestId}',
    request_fingerprint  CHAR(64)        CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '写操作参数 SHA-256 指纹，用于同键异参冲突检测',
    operator_type        VARCHAR(32)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id          BIGINT          NULL,
    reason               VARCHAR(256)    NULL,
    detail_json          TEXT            NULL COMMENT '仅存脱敏及状态摘要，严禁记录明文敏感字段',
    created_at           DATETIME(3)     NOT NULL,
    UNIQUE KEY uk_receiver_audit_req (request_key),
    KEY idx_receiver_audit_merchant (merchant_id, created_at),
    KEY idx_receiver_audit_account (receiver_account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
