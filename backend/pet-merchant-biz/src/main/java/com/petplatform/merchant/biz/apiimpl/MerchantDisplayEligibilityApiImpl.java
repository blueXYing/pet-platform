package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsApi;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsQuery;
import com.petplatform.merchant.biz.application.MerchantEligibilityFactsReader;
import com.petplatform.merchant.biz.application.MerchantDisplayEligibilityService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantMybatis;
import java.util.List;
import javax.sql.DataSource;

/**
 * SVC-D5 local implementation: display-only facts over the same eligibility machinery as the
 * owner-scoped contract, minus the ownership predicate. See the interface contract for the
 * NOT_FOUND versus DEPENDENCY_UNAVAILABLE split that callers must preserve. Reads join the
 * caller's active transaction when one exists (single-snapshot consistency).
 *
 * <p>Also implements the contract-74 category-availability supplement
 * ({@link MerchantDisplayStoreIdsApi}): the same visibility policy projected as a bulk store-id
 * read so cross-domain category counts never need a query per category or per store.
 */
public final class MerchantDisplayEligibilityApiImpl
        implements MerchantDisplayEligibilityApi, MerchantDisplayStoreIdsApi {
    private final MerchantDisplayEligibilityService service;

    public MerchantDisplayEligibilityApiImpl(
            DataSource dataSource, MerchantEligibilityFactsReader eligibilityFacts) {
        this.service = new MerchantDisplayEligibilityService(
                MerchantMybatis.joiningTemplate(dataSource), eligibilityFacts);
    }

    @Override
    public MerchantDisplayEligibilityDTO checkDisplayEligibility(MerchantDisplayEligibilityQuery query) {
        return service.check(query);
    }

    @Override
    public List<String> listDisplayStoreIds(MerchantDisplayStoreIdsQuery query) {
        return service.listDisplayStoreIds(query);
    }
}
