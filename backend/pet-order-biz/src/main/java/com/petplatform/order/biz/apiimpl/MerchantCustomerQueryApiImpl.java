package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerDetail;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerGetQuery;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerListQuery;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerRow;
import com.petplatform.order.biz.application.MerchantCustomerQueryService;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.UserQueryApi;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the derived customer read face (contract 69). Read-only: the store
 * guard and the OWNER coordinate re-proof run inside the same read transaction as the
 * aggregate statements, mirroring the §4.1 list discipline. The user basics projection is
 * borrowed through the user domain's own masked batch read (no credential ever crosses).
 */
public final class MerchantCustomerQueryApiImpl implements MerchantCustomerQueryApi {
    private final MerchantCustomerQueryService service;

    public MerchantCustomerQueryApiImpl(DataSource dataSource, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, UserQueryApi users) {
        this.service = new MerchantCustomerQueryService(
                new OrderQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(guard, "guard is required"),
                Objects.requireNonNull(authority, "authority is required"),
                Objects.requireNonNull(users, "user basics facts are required"));
    }

    @Override
    public PageResult<CustomerRow> listCustomers(CustomerListQuery query) {
        return service.listCustomers(query);
    }

    @Override
    public CustomerDetail getCustomer(CustomerGetQuery query) {
        return service.getCustomer(query);
    }
}
