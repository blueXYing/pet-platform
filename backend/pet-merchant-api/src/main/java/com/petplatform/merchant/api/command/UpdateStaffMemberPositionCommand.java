package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

/**
 * CCR-M-STAFF-POS-001 (2026-11-16): OWNER edits the member's free-text position label
 * (0..64 code points, '' allowed). The label is a display fact only — it never feeds any
 * action gate; expectedVersion is the member version and the write bumps it.
 */
public record UpdateStaffMemberPositionCommand(String merchantId, String storeId, String memberId,
                                               String position, String expectedVersion,
                                               CommandContext context) {}
