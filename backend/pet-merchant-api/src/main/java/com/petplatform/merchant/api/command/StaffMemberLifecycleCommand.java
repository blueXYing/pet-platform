package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

/**
 * Shared shape of disableMember / enableMember / revokeStoreGrant (contract 54 §3). The
 * expectedVersion is the member version for disable/enable and the grant version for
 * revokeStoreGrant.
 */
public record StaffMemberLifecycleCommand(String merchantId, String storeId, String memberId,
                                          String expectedVersion, CommandContext context) {}
