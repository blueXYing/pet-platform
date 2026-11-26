-- CCR-PAY-CENTER-001 §14（2026-11-26 模式 A：商家独立收单路由）：
-- 为商家结算配置表补充按商家粒度的拉卡拉银联收单商户号与终端号（成对配置），并同步补充审计快照列。
-- 与 backend/pet-boot/src/main/resources/db/settlement-migration/V43__payment_merchant_acquiring.sql 保持一致。

ALTER TABLE settlement_config
    ADD COLUMN lakala_merchant_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商家拉卡拉银联收单商户号（模式 A）' AFTER enabled,
    ADD COLUMN lakala_term_no     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商家拉卡拉收单终端号（模式 A）' AFTER lakala_merchant_no,
    ADD KEY idx_settlement_config_lkl_merchant (lakala_merchant_no),
    ADD CONSTRAINT ck_settlement_config_lkl_pair CHECK (
        (lakala_merchant_no IS NULL AND lakala_term_no IS NULL)
        OR (lakala_merchant_no IS NOT NULL AND lakala_term_no IS NOT NULL)
    );

ALTER TABLE settlement_config_audit
    ADD COLUMN before_lakala_merchant_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER after_enabled,
    ADD COLUMN after_lakala_merchant_no  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER before_lakala_merchant_no,
    ADD COLUMN before_lakala_term_no     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER after_lakala_merchant_no,
    ADD COLUMN after_lakala_term_no      VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER before_lakala_term_no;
