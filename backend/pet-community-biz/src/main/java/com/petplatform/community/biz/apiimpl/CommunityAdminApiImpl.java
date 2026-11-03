package com.petplatform.community.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.community.api.CommunityAdminApi;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.community.biz.application.CommunityAdminAuthorizationPort;
import com.petplatform.community.biz.application.CommunityAdminService;
import com.petplatform.community.biz.application.port.CommunityMediaObjectStore;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort;
import com.petplatform.community.biz.infrastructure.persistence.CommunityStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of contract 73 admin community moderation. */
public final class CommunityAdminApiImpl implements CommunityAdminApi {

    private final CommunityAdminService service;

    public CommunityAdminApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            CommunityAdminAuthorizationPort authorization, CommunityUserDirectoryPort users,
            CommunityMediaObjectStore mediaStore, Clock clock, String mediaBaseUrl) {
        this.service = new CommunityAdminService(
                new CommunityStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(users, "users is required"),
                Objects.requireNonNull(mediaStore, "mediaStore is required"), clock, mediaBaseUrl);
    }

    @Override public CommunityViews.Page<CommunityViews.AdminPostView> listPosts(AdminPostListQuery query) { return service.listPosts(query); }
    @Override public CommunityViews.Receipt moderatePost(ModeratePostCommand command) { return service.moderatePost(command); }
    @Override public CommunityViews.Page<CommunityViews.AdminReportView> listReports(AdminReportListQuery query) { return service.listReports(query); }
    @Override public CommunityViews.Receipt resolveReport(ResolveReportCommand command) { return service.resolveReport(command); }
}
