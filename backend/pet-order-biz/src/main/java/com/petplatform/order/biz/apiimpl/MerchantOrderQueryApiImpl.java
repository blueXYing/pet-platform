package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi.StoreOrderListQuery;
import com.petplatform.order.api.query.MerchantOrderQueryApi.Summary;
import com.petplatform.order.biz.application.MerchantOrderQueryService;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the merchant order list read surface (HTTP contract 10 §4.1
 * supplement). Read-only: the store guard and the OWNER merchant coordinate re-proof run
 * inside the same read transaction as the page statements, mirroring the contract-45
 * command-side discipline.
 */
public final class MerchantOrderQueryApiImpl implements MerchantOrderQueryApi {
    private final MerchantOrderQueryService service;

    public MerchantOrderQueryApiImpl(DataSource dataSource, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority) {
        this.service = new MerchantOrderQueryService(
                new OrderQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(guard, "guard is required"),
                Objects.requireNonNull(authority, "authority is required"));
    }

    @Override
    public PageResult<Summary> listStoreOrders(StoreOrderListQuery query) {
        return service.listStoreOrders(query);
    }
}
