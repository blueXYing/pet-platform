package com.petplatform.verification.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.api.query.MerchantStaffNamingApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.StoreVerificationListQuery;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.VerificationRow;
import com.petplatform.verification.biz.application.MerchantVerificationReadService;
import com.petplatform.verification.biz.infrastructure.persistence.MerchantVerificationReadStore;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the CCR-M-VERIFY-RECORD-001 merchant verification-record list face.
 * Read-only: the schedule store guard, the OWNER coordinate re-proof and the page statements
 * follow the contract-10 §4.1 order-list discipline; the STAFF display names borrow the
 * merchant domain's naming face (no biz depends on another biz — both are api-layer ports).
 */
public final class MerchantVerificationReadApiImpl implements MerchantVerificationReadApi {
    private final MerchantVerificationReadService service;

    public MerchantVerificationReadApiImpl(DataSource dataSource, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, MerchantStaffNamingApi naming) {
        this.service = new MerchantVerificationReadService(
                new MerchantVerificationReadStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(guard, "guard is required"),
                Objects.requireNonNull(authority, "authority is required"),
                Objects.requireNonNull(naming, "naming is required"));
    }

    @Override
    public PageResult<VerificationRow> listStoreVerifications(StoreVerificationListQuery query) {
        return service.listStoreVerifications(query);
    }
}
