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

    record UpdateNoteCommand(String merchantId, String storeId, String userId, String note,
            String expectedVersion, CommandContext context) {}
}
