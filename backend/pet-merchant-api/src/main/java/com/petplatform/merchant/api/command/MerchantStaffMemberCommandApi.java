package com.petplatform.merchant.api.command;

import com.petplatform.merchant.api.dto.MerchantStaffInvitationCommandResult;
import com.petplatform.merchant.api.dto.MerchantStaffMemberCommandResult;

/**
 * Contract 54 write side: D1-a invitation-confirm binding plus the approved D2 owner commands.
 * Every method is supplement-23 idempotent and fails closed; member REVOKED does not exist in
 * this slice (D3 pending), so revokeStoreGrant and cancelInvitation are terminal.
 */
public interface MerchantStaffMemberCommandApi {

    MerchantStaffInvitationCommandResult inviteMember(InviteStaffMemberCommand command);

    MerchantStaffInvitationCommandResult cancelInvitation(CancelStaffMemberInvitationCommand command);

    MerchantStaffMemberCommandResult disableMember(StaffMemberLifecycleCommand command);

    MerchantStaffMemberCommandResult enableMember(StaffMemberLifecycleCommand command);

    MerchantStaffMemberCommandResult grantActions(GrantStaffMemberActionsCommand command);

    MerchantStaffMemberCommandResult revokeStoreGrant(StaffMemberLifecycleCommand command);

    /** Employee-side confirm; operator is the session user, not the owner. */
    MerchantStaffMemberCommandResult confirmInvitation(ConfirmStaffMemberInvitationCommand command);
}
