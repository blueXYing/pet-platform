package com.petplatform.points.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.points.api.query.AdminPointsQueryApi;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort.Port;
import com.petplatform.points.biz.application.AdminPointsQueryService;
import com.petplatform.points.biz.infrastructure.persistence.AdminPointsQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-65 platform points ledger read face. */
public final class AdminPointsQueryApiImpl implements AdminPointsQueryApi {

    private final AdminPointsQueryService service;

    public AdminPointsQueryApiImpl(DataSource dataSource, Port authorization) {
        this.service = new AdminPointsQueryService(
                new AdminPointsQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(authorization, "authorization is required"));
    }

    @Override
    public PageResult<AdminLedgerRow> queryLedger(AdminLedgerListQuery query) {
        return service.queryLedger(query);
    }
}
