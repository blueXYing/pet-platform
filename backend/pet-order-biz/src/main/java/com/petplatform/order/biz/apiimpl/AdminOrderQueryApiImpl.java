package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.order.api.query.AdminOrderQueryApi;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetail;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetailQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderListQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderSummary;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminStatusLogQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.StatusLog;
import com.petplatform.order.biz.application.AdminOrderQueryService;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-63 platform order read face (HTTP10 §5.11). */
public final class AdminOrderQueryApiImpl implements AdminOrderQueryApi {
    private final AdminOrderQueryService service;

    public AdminOrderQueryApiImpl(
            DataSource dataSource, AdminTradeAuthorizationPort.OrderAuthorizationPort authorization) {
        this(dataSource, authorization, java.time.Clock.systemUTC());
    }

    public AdminOrderQueryApiImpl(
            DataSource dataSource,
            AdminTradeAuthorizationPort.OrderAuthorizationPort authorization,
            java.time.Clock clock) {
        this.service = new AdminOrderQueryService(
                new com.petplatform.order.biz.infrastructure.persistence.AdminOrderQueryStore(
                        Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                clock);
    }

    @Override
    public PageResult<AdminOrderSummary> listOrders(AdminOrderListQuery query) {
        return service.listOrders(query);
    }

    @Override
    public AdminOrderDetail getOrder(AdminOrderDetailQuery query) {
        return service.getOrder(query);
    }

    @Override
    public PageResult<StatusLog> listStatusLogs(AdminStatusLogQuery query) {
        return service.listStatusLogs(query);
    }
}
