package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.order.api.dto.OrderSnapshotDTO;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.order.api.query.OrderQueryApi.MyOrderListQuery;
import com.petplatform.order.api.query.OrderQueryApi.OrderIdQuery;
import com.petplatform.order.biz.application.OrderQueryService;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the C-004 read-only order query surface (HTTP contract 10 §3.7). */
public final class OrderQueryApiImpl implements OrderQueryApi {
    private final OrderQueryService service;

    public OrderQueryApiImpl(DataSource dataSource) {
        this.service = new OrderQueryService(
                new OrderQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")));
    }

    @Override
    public OrderSnapshotDTO getOrder(OrderIdQuery query) {
        return service.getOrder(query);
    }

    @Override
    public PageResult<OrderSnapshotDTO> listMyOrders(MyOrderListQuery query) {
        return service.listMyOrders(query);
    }
}
