package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantStaffInvitationDetailDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationPageDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMemberPageDTO;

/** Contract 54 §4 read side: OWNER management lists plus the employee confirm-page detail. */
public interface MerchantStaffMemberManagementQueryApi {

    MerchantStaffMemberPageDTO listMembers(StaffMemberManagementQuery query);

    MerchantStaffInvitationPageDTO listInvitations(StaffInvitationManagementQuery query);

    /** Anti-enumeration: only the session whose verified phone matches the invitation target. */
    MerchantStaffInvitationDetailDTO getMyInvitation(MyStaffInvitationQuery query);
}
