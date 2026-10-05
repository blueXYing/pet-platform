package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.dto.MerchantStaffIdentityFactsDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantStaffActionQuery;
import com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery;
import com.petplatform.merchant.api.query.MerchantStaffIdentityQueryApi;
import com.petplatform.merchant.api.query.MerchantStaffMembershipQuery;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantStaffIdentityService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffIdentityStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.Clock;
import javax.sql.DataSource;

/** Default-off internal staff identity facts; no HTTP surface ships in this slice. */
public final class MerchantStaffIdentityApiImpl implements MerchantStaffIdentityQueryApi {
    private final MerchantStaffIdentityService service;

    public MerchantStaffIdentityApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            ApplicationReviewFactsReader applicationFacts, Clock clock) {
        this.service = new MerchantStaffIdentityService(new MerchantStaffIdentityStore(source), guard,
                applicationFacts, clock);
    }

    @Override
    public MerchantStaffMembershipPageDTO listStaffMemberships(MerchantStaffMembershipQuery query) {
        return service.listStaffMemberships(query);
    }

    @Override
    public MerchantStaffIdentityFactsDTO getStaffFacts(MerchantStaffIdentityFactsQuery query) {
        return service.getStaffFacts(query);
    }

    @Override
    public void requireStaffAction(MerchantStaffActionQuery query) {
        service.requireStaffAction(query);
    }

    public static ApplicationReviewFactsReader unavailableApplicationFacts() {
        return merchantId -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application review facts are not configured");
        };
    }
}
