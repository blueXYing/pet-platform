package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.InstructionRow;

/**
 * Single source of truth for distinguishing Mode A (merchant-acquiring + reverse split to platform,
 * Ruling 40 §10 / CCR-PAY-CENTER-001 §14) from legacy platform-acquiring settlement instructions.
 */
final class SettlementModeDetector {
    private SettlementModeDetector() {}

    static boolean isModeAInstruction(InstructionRow instr, String platformMerchantNo, String platformReceiverNo) {
        if (instr == null) {
            return false;
        }
        return isModeA(instr.merchantNo, instr.receiverNo, platformMerchantNo, platformReceiverNo);
    }

    static boolean isModeA(String instructionMerchantNo, String instructionReceiverNo,
            String platformMerchantNo, String platformReceiverNo) {
        if (instructionReceiverNo == null || instructionReceiverNo.isBlank()) {
            return true;
        }
        if (platformReceiverNo != null && !platformReceiverNo.isBlank()
                && platformReceiverNo.equals(instructionReceiverNo)) {
            return true;
        }
        return platformMerchantNo != null && !platformMerchantNo.isBlank()
                && instructionMerchantNo != null && !instructionMerchantNo.isBlank()
                && !platformMerchantNo.equals(instructionMerchantNo);
    }
}
