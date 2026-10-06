package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;
import java.util.List;

/** Contract 54 §3: whole-set replacement of the store actions; expectedVersion is grant version. */
public record GrantStaffMemberActionsCommand(String merchantId, String storeId, String memberId,
                                             List<String> actions, String expectedVersion,
                                             CommandContext context) {}
