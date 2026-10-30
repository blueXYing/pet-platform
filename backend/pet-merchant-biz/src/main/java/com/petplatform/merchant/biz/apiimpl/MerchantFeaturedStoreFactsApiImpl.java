package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi;
import com.petplatform.merchant.biz.application.MerchantEligibilityFactsReader;
import com.petplatform.merchant.biz.application.MerchantFeaturedStoreFactsService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantMybatis;
import java.util.List;
import javax.sql.DataSource;

/**
 * Contract 74 §3.4 local implementation of the featured-card store facts port (B-CARD-02,
 * MerchantDisplayStoreIdsApi pattern): display-only merchant-owned card facts over the same
 * eligibility machinery as the owner-scoped contracts, minus the ownership predicate. See the
 * interface contract for the absent-versus-unavailable split callers must preserve. Reads join the
 * caller's active transaction when one exists (single-snapshot consistency).
 */
public final class MerchantFeaturedStoreFactsApiImpl implements MerchantFeaturedStoreFactsApi {
    private final MerchantFeaturedStoreFactsService service;

    public MerchantFeaturedStoreFactsApiImpl(
            DataSource dataSource, MerchantEligibilityFactsReader eligibilityFacts) {
        this.service = new MerchantFeaturedStoreFactsService(
                MerchantMybatis.joiningTemplate(dataSource), eligibilityFacts);
    }

    @Override
    public List<FeaturedStoreFact> listFeaturedStoreFacts(FeaturedStoreFactsQuery query) {
        return service.listFeaturedStoreFacts(query);
    }
}
