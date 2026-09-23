package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.merchant.biz.application.MerchantEligibilityFactsReader;
import com.petplatform.merchant.biz.application.MerchantStoreDisplayService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import javax.sql.DataSource;

/**
 * STR-D6 local implementation of the fifth merchant query: display-only store projections over
 * the same eligibility machinery as the owner-scoped contracts, minus the ownership predicate.
 * Reads execute in the merchant module's own read-only repeatable-read snapshot. See the
 * interface contract for the NOT_FOUND versus DEPENDENCY_UNAVAILABLE split that callers must
 * preserve, including the compat-row integrity counterexample (whole-page fail closed).
 */
public final class MerchantStoreDisplayApiImpl implements MerchantStoreDisplayApi {
    private final MerchantStoreDisplayService service;

    public MerchantStoreDisplayApiImpl(
            DataSource dataSource, MerchantEligibilityFactsReader eligibilityFacts) {
        this.service = new MerchantStoreDisplayService(
                new MerchantReadStore(dataSource), eligibilityFacts);
    }

    @Override
    public MerchantStoreDisplayPageDTO pageDisplayStores(MerchantStoreDisplayPageQuery query) {
        return service.page(query);
    }

    @Override
    public MerchantStoreDisplayDTO getDisplayStore(MerchantStoreDisplayQuery query) {
        return service.get(query);
    }
}
