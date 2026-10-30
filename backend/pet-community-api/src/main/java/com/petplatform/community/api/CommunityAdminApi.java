package com.petplatform.community.api;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.community.api.dto.CommunityViews;
import java.util.List;

/** CCR-COM-001 platform-ops moderation/admin face. */
public interface CommunityAdminApi {

    CommunityViews.Page<CommunityViews.AdminPostView> listPosts(AdminPostListQuery query);

    CommunityViews.Receipt moderatePost(ModeratePostCommand command);

    CommunityViews.Page<CommunityViews.AdminReportView> listReports(AdminReportListQuery query);

    CommunityViews.Receipt resolveReport(ResolveReportCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration, String operatorId,
            String scopeMode, List<String> cityCodes) {
        public AdminAuthorization {
            scopeMode = scopeMode == null || scopeMode.isBlank() ? "ALL" : scopeMode;
            cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
        }
    }

    record AdminPostListQuery(String status, String keyword, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record AdminReportListQuery(String status, int page, int pageSize,
            AdminAuthorization authorization, QueryContext context) {}

    record ModeratePostCommand(String postId, String decision, String reason, long expectedVersion,
            AdminAuthorization authorization, CommandContext context) {}

    record ResolveReportCommand(String reportId, String decision, String note,
            AdminAuthorization authorization, CommandContext context) {}
}
