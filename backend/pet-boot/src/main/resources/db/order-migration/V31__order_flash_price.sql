-- V31__order_flash_price.sql（CCR-SVC-FLASH-PRICE-001 / 契约77 §6.5）
-- pet_order / order_service_snapshot 闪价快照列；存量按 LIST 语义回填。
-- order-migration 目录为新增（各模块迁移版本号独立）。

ALTER TABLE pet_order
    ADD COLUMN original_price DECIMAL(18,2) NULL COMMENT '下单时服务原价（listPrice）' AFTER original_amount,
    ADD COLUMN flash_discount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '原价−闪价；无闪价=0' AFTER original_price,
    ADD COLUMN price_source VARCHAR(16) NOT NULL DEFAULT 'LIST' COMMENT 'LIST/FLASH' AFTER flash_discount,
    ADD COLUMN flash_price_id BIGINT NULL COMMENT '闪价记录' AFTER price_source;

UPDATE pet_order SET original_price = original_amount WHERE original_price IS NULL;

ALTER TABLE pet_order
    MODIFY COLUMN original_price DECIMAL(18,2) NOT NULL COMMENT '下单时服务原价（listPrice）';

ALTER TABLE order_service_snapshot
    ADD COLUMN service_list_price DECIMAL(18,2) NULL COMMENT '下单时服务原价' AFTER service_price,
    ADD COLUMN service_price_source VARCHAR(16) NULL COMMENT 'LIST/FLASH' AFTER service_list_price,
    ADD COLUMN service_flash_price_id BIGINT NULL COMMENT '闪价记录' AFTER service_price_source;
