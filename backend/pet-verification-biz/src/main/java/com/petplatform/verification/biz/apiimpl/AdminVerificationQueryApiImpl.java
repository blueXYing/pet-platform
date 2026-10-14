package com.petplatform.verification.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.verification.api.query.AdminVerificationQueryApi;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationDetail;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationListQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationSummary;
import com.petplatform.verification.biz.application.AdminTradeAuthorizationPort;
import com.petplatform.verification.biz.application.AdminVerificationQueryService;
import com.petplatform.verification.biz.infrastructure.persistence.AdminVerificationQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-63 platform verification read face (HTTP10 §5.1). */
public final class AdminVerificationQueryApiImpl implements AdminVerificationQueryApi {
    private final AdminVerificationQueryService service;

    public AdminVerificationQueryApiImpl(DataSource dataSource, AdminTradeAuthorizationPort.VerificationAuthorizationPort authorization) {
        this.service = new AdminVerificationQueryService(
                new AdminVerificationQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(authorization, "authorization is required"));
    }

    @Override
    public PageResult<AdminVerificationSummary> listVerifications(AdminVerificationListQuery query) {
        return service.listVerifications(query);
    }

    @Override
    public AdminVerificationDetail getVerification(AdminVerificationQuery query) {
        return service.getVerification(query);
    }
}
