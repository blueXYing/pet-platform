package com.petplatform.order.api.query;

import com.petplatform.common.CommandContext;

/**
 * Merchant-internal customer note write face (contract 69 expansion, 2026-10-19 CCR ruling):
 * exactly one note per (store, customer) derived row, 0..200 code points, OWNER writes only.
 * Supplement-23 idempotency on the requestId, optimistic version on the note row, same-row
 * audit (field names only), and the real-OWNER write authority inside the store guard. The
 * empty string clears the note (stored NULL).
 */
public interface MerchantCustomerNoteCommandApi {

    MerchantCustomerQueryApi.NoteView updateNote(UpdateNoteCommand command);

    // 2026-11-18 用户裁决：商家客户备注放弃乐观版本保护，直接覆盖（last-write-wins）；
    // requestId 幂等（防重复提交）与 OWNER 鉴权不变。
    record UpdateNoteCommand(String merchantId, String storeId, String userId, String note,
            CommandContext context) {}
}
