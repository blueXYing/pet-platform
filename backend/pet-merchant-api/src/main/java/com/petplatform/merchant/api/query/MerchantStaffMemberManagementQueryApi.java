package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantStaffInvitationDetailDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationListPageDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationPageDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMemberPageDTO;

/** Contract 54 §4/§7 read side: OWNER management lists plus the employee-side reads. */
public interface MerchantStaffMemberManagementQueryApi {

    MerchantStaffMemberPageDTO listMembers(StaffMemberManagementQuery query);

    MerchantStaffInvitationPageDTO listInvitations(StaffInvitationManagementQuery query);

    /** Anti-enumeration: only the session whose verified phone matches the invitation target. */
    MerchantStaffInvitationDetailDTO getMyInvitation(MyStaffInvitationQuery query);

    /**
     * Contract 54 §7 employee-side list: invitations whose phone equals the session user's
     * verified account phone (history included). A session without a match — other phone, no
     * phone fact — reads the same empty page; existence is never disclosed.
     */
    MerchantStaffInvitationListPageDTO listMyInvitations(MyStaffInvitationPageQuery query);
}
