package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

/** Contract 54 §3: owner cancels a still-INVITED invitation; terminal (D3 pending). */
public record CancelStaffMemberInvitationCommand(String merchantId, String storeId,
                                                 String invitationId, String expectedVersion,
                                                 CommandContext context) {}
