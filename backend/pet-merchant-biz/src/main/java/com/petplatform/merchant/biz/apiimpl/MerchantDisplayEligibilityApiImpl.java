package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityQuery;
import com.petplatform.merchant.biz.application.MerchantEligibilityFactsReader;
import com.petplatform.merchant.biz.application.MerchantDisplayEligibilityService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantMybatis;
import javax.sql.DataSource;

/**
 * SVC-D5 local implementation: display-only facts over the same eligibility machinery as the
 * owner-scoped contract, minus the ownership predicate. See the interface contract for the
 * NOT_FOUND versus DEPENDENCY_UNAVAILABLE split that callers must preserve. Reads join the
 * caller's active transaction when one exists (single-snapshot consistency).
 */
public final class MerchantDisplayEligibilityApiImpl implements MerchantDisplayEligibilityApi {
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
}
