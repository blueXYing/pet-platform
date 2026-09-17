package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;
import com.petplatform.merchant.api.query.MerchantOrderEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import com.petplatform.merchant.api.query.MerchantStaffQuery;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.merchant.biz.application.MerchantEligibilityFactsReader;
import com.petplatform.merchant.biz.application.MerchantQueryService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the approved three-query merchant contract. */
public final class MerchantQueryApiImpl implements MerchantQueryApi {
    private final MerchantQueryService service;

    /**
     * Safe default while the approved application fact has no physical contract: store and staff
     * reads work, while new-order eligibility explicitly reports an unavailable dependency.
     */
    public MerchantQueryApiImpl(DataSource dataSource) {
        this(dataSource, (merchantId, storeId) -> {
            throw new ApiException(
                    CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application eligibility facts are not wired"
            );
        });
    }

    /** Facts reader is invoked within the same repeatable-read transaction as merchant/store reads. */
    public MerchantQueryApiImpl(DataSource dataSource, MerchantEligibilityFactsReader eligibilityFacts) {
        this.service = new MerchantQueryService(
                new MerchantReadStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(eligibilityFacts, "eligibilityFacts is required")
        );
    }

    @Override
    public MerchantStoreDTO getStore(StoreIdQuery query) {
        return service.getStore(query);
    }

    @Override
    public MerchantOrderEligibilityDTO checkOrderEligibility(MerchantOrderEligibilityQuery query) {
        return service.checkOrderEligibility(query);
    }

    @Override
    public MerchantStaffDTO getStaff(MerchantStaffQuery query) {
        return service.getStaff(query);
    }
}
