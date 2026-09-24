package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;
import com.petplatform.merchant.api.query.MerchantStoreStaffFactsApi;
import com.petplatform.merchant.api.query.StoreStaffFactsQuery;
import com.petplatform.merchant.biz.application.MerchantStoreStaffFactsService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Sixth MER internal query, scoped to ID-only schedule facts. */
public final class MerchantStoreStaffFactsApiImpl implements MerchantStoreStaffFactsApi {
    private final MerchantStoreStaffFactsService service;

    public MerchantStoreStaffFactsApiImpl(DataSource source) {
        this.service = new MerchantStoreStaffFactsService(
                new MerchantReadStore(Objects.requireNonNull(source, "source is required")));
    }

    @Override
    public StoreStaffFactsDTO listActiveStoreStaffFacts(StoreStaffFactsQuery query) {
        return service.list(query);
    }
}
