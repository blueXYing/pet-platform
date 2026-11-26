-- CCR-PAY-CENTER-001 阶段 3：分账回退（2395）与退款前置关联字段。
-- 契约：docs/04-api/78-Payment-Settlement-Contract-v0.1.md（§8 阶段 3 补充）。
-- Apply after 78-Payment-Settlement-Schema-v0.1.sql in an isolated test database.
-- 对应 Flyway 迁移：backend/pet-boot/src/main/resources/db/settlement-migration/V39__payment_settlement_fallback.sql。

ALTER TABLE settlement_instruction
    ADD COLUMN refund_order_id BIGINT NULL COMMENT '关联 refund_order.id（仅 FALLBACK 指令）' AFTER payment_id,
    ADD COLUMN origin_instruction_id BIGINT NULL COMMENT '原成功 SPLIT 指令 ID（仅 FALLBACK 指令）' AFTER refund_order_id,
    ADD COLUMN fallback_reason VARCHAR(128) NULL COMMENT '2395 回退原因' AFTER acc_result_desc,
    ADD KEY idx_settlement_instr_refund (refund_order_id, attempt_no),
    ADD KEY idx_settlement_instr_origin (origin_instruction_id);
