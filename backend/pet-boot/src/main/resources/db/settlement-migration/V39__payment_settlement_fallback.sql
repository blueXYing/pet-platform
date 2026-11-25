-- CCR-PAY-CENTER-001 阶段 3 隔离测试库迁移：分账回退指令关联列。
-- 与 docs/03-database/79-Payment-Settlement-Fallback-Schema-v0.1.sql 保持一字不差。

ALTER TABLE settlement_instruction
    ADD COLUMN refund_order_id BIGINT NULL COMMENT '关联 refund_order.id（仅 FALLBACK 指令）' AFTER payment_id,
    ADD COLUMN origin_instruction_id BIGINT NULL COMMENT '原成功 SPLIT 指令 ID（仅 FALLBACK 指令）' AFTER refund_order_id,
    ADD COLUMN fallback_reason VARCHAR(128) NULL COMMENT '2395 回退原因' AFTER acc_result_desc,
    ADD KEY idx_settlement_instr_refund (refund_order_id, attempt_no),
    ADD KEY idx_settlement_instr_origin (origin_instruction_id);
