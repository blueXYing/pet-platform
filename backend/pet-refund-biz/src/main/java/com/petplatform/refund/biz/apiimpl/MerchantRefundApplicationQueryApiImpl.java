package com.petplatform.refund.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationListQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.Summary;
import com.petplatform.refund.biz.application.MerchantRefundApplicationQueryService;
import com.petplatform.refund.biz.application.RefundApplicationPorts;
import com.petplatform.refund.biz.infrastructure.persistence.RefundApplicationQueryStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the merchant refund-application read surfaces (contract 57).
 * Read-only: the store guard and the OWNER re-proof run inside the same read transaction
 * as the page statements, mirroring the contract-45/§4.1 command and read discipline.
 */
public final class MerchantRefundApplicationQueryApiImpl implements MerchantRefundApplicationQueryApi {
    private final MerchantRefundApplicationQueryService service;

    public MerchantRefundApplicationQueryApiImpl(DataSource dataSource,
            ScheduleCapacityGuardApi guard, RefundApplicationPorts.OwnerReadAuthority ownerRead) {
        this.service = new MerchantRefundApplicationQueryService(
                new RefundApplicationQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(guard, "guard is required"),
                Objects.requireNonNull(ownerRead, "ownerRead is required"));
    }

    @Override
    public PageResult<Summary> listStoreApplications(StoreApplicationListQuery query) {
        return service.listStoreApplications(query);
    }

    @Override
    public Summary readStoreApplication(StoreApplicationQuery query) {
        return service.readStoreApplication(query);
    }
}
