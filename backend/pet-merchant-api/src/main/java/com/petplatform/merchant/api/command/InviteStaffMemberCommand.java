package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;
import java.util.List;

/** Contract 54 §3: owner registers an invitation (phone + name + V1-catalog action set). */
public record InviteStaffMemberCommand(String merchantId, String storeId, String phone,
                                       String memberName, List<String> actions,
                                       CommandContext context) {}
