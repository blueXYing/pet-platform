package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetail;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewPage;
import com.petplatform.service.api.query.ServiceReviewQueryApi;
import com.petplatform.service.biz.application.ServiceAdmissionGate;
import com.petplatform.service.biz.application.ServiceAdminQueryService;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import java.time.Clock;
import javax.sql.DataSource;

/** Local implementation of the admin review reads (A-002). */
public final class ServiceReviewQueryApiImpl implements ServiceReviewQueryApi {
    private final ServiceAdminQueryService service;

    public ServiceReviewQueryApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            Clock clock) {
        this.service =
                new ServiceAdminQueryService(
                        new ServiceWriteStore(dataSource, ids),
                        new ServiceAdmissionGate(admissions),
                        clock);
    }

    @Override
    public ServiceReviewPage listForReview(ServiceReviewListQuery query) {
        return service.listForReview(query);
    }

    @Override
    public ServiceReviewDetail getForReview(ServiceReviewDetailQuery query) {
        return service.getForReview(query);
    }
}
