package com.petplatform.service.biz.apiimpl;

import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.service.biz.application.ServiceQueryService;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.service.biz.infrastructure.persistence.ServiceReadStore;
import javax.sql.DataSource;

/** Local implementation of the approved service-domain read contract. */
public final class ServiceQueryApiImpl implements ServiceQueryApi {
    private final ServiceQueryService service;

    public ServiceQueryApiImpl(
            DataSource dataSource,
            MerchantDisplayEligibilityApi merchantFacts,
            ServiceCoverUrlPort coverUrls) {
        this.service =
                new ServiceQueryService(new ServiceReadStore(dataSource), merchantFacts, coverUrls);
    }

    @Override
    public ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query) {
        return service.snapshot(query);
    }

    @Override
    public ServiceBookabilityDTO checkBookable(ServiceBookabilityQuery query) {
        return service.bookability(query);
    }

    @Override
    public ServiceSnapshotPageDTO getStoreServiceSnapshots(StoreServiceSnapshotQuery query) {
        return service.storePage(query);
    }

    @Override
    public ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query) {
        return service.visibleService(query);
    }
}
