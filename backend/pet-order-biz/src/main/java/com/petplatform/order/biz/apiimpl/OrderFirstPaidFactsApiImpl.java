package com.petplatform.order.biz.apiimpl;

import com.petplatform.order.api.dto.OrderFirstPaidFact;
import com.petplatform.order.api.query.OrderFirstPaidFactsApi;
import com.petplatform.order.biz.application.OrderQueryService;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the order-owned first-paid-order fact port (07号 §7.1 amendment,
 * contract 60 §7 / CCR-POINTS-ORDER-001 O(1) closure). Read-only, subject-free: the same
 * single-truth query service as the C-004 read slice answers it over the module's own store.
 */
public final class OrderFirstPaidFactsApiImpl implements OrderFirstPaidFactsApi {
    private final OrderQueryService service;

    public OrderFirstPaidFactsApiImpl(DataSource dataSource) {
        this.service = new OrderQueryService(
                new OrderQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")));
    }

    @Override
    public OrderFirstPaidFact firstPaidOrder(UserOrderFactQuery query) {
        return service.firstPaidOrder(query);
    }
}
