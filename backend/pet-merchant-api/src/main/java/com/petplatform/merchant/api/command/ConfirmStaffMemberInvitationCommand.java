package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

/**
 * Contract 54 §3 D1-a: the invited user confirms with their own logged-in session. Phone
 * equality against the invitation is re-verified server-side inside the confirm transaction.
 */
public record ConfirmStaffMemberInvitationCommand(String invitationId, CommandContext context) {}
