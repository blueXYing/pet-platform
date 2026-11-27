-- V32__order_coupon_funding.sql（CCR-MKT-MERCHANT-COUPON-001 / 契约79 §6.2）
-- pet_order 券出资方与商家券面额快照列；与 docs/03-database/84-Merchant-Coupon-Schema-v0.1.sql 第 4 节严格一致。
-- 存量订单：coupon_funding_type 保持 NULL（视为平台券或无券）、merchant_coupon_discount = 0.00，结算结果不变。

ALTER TABLE pet_order
    ADD COLUMN coupon_funding_type     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'PLATFORM/MERCHANT；无券为 NULL' AFTER discount_amount,
    ADD COLUMN merchant_coupon_discount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '出资方为商家的券面额；无商家券为 0.00' AFTER coupon_funding_type;
