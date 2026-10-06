package com.petplatform.points.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.points.api.dto.PointsBalanceDTO;
import com.petplatform.points.api.dto.PointsLedgerDTO;
import com.petplatform.points.api.query.PointsQueryApi;
import com.petplatform.points.biz.application.PointsQueryService;
import com.petplatform.points.biz.infrastructure.persistence.PointsQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the CCR-C006 P1 C-end points read surface (read-only). */
public final class PointsQueryApiImpl implements PointsQueryApi {
    private final PointsQueryService service;

    public PointsQueryApiImpl(DataSource dataSource) {
        this.service = new PointsQueryService(
                new PointsQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")));
    }

    @Override
    public PointsBalanceDTO getBalance(PointsBalanceQuery query) {
        return service.getBalance(query);
    }

    @Override
    public PageResult<PointsLedgerDTO> queryLedger(PointsLedgerQuery query) {
        return service.queryLedger(query);
    }
}
