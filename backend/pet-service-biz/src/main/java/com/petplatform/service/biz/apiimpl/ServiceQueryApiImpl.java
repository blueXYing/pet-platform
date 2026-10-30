package com.petplatform.service.biz.apiimpl;

import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsApi;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryPage;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryStores;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.query.CatalogServiceCategoryQuery;
import com.petplatform.service.api.query.CatalogServiceCategoryStoresQuery;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceCatalogCategoryApi;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.service.biz.application.ServiceQueryService;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.service.biz.infrastructure.persistence.ServiceReadStore;
import javax.sql.DataSource;

/**
 * Local implementation of the approved service-domain read contract, plus the contract-74 C
 * category read port (B-SVC-03/04). Both faces share one {@link ServiceQueryService} so the
 * category filter and the service visibility check always use the same conjunction and snapshot
 * machinery.
 */
public final class ServiceQueryApiImpl implements ServiceQueryApi, ServiceCatalogCategoryApi {
    private final ServiceQueryService service;

    public ServiceQueryApiImpl(
            DataSource dataSource,
            MerchantDisplayEligibilityApi merchantFacts,
            MerchantDisplayStoreIdsApi displayStoreIds,
            ServiceCoverUrlPort coverUrls) {
        this.service = new ServiceQueryService(
                new ServiceReadStore(dataSource), merchantFacts, displayStoreIds, coverUrls);
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

    @Override
    public ServiceSnapshotPageDTO searchVisibleServices(
            com.petplatform.service.api.query.CatalogServiceSearchQuery query) {
        return service.catalogSearch(query);
    }

    @Override
    public CatalogServiceCategoryPage listCatalogCategories(CatalogServiceCategoryQuery query) {
        return service.catalogCategories(query);
    }

    @Override
    public CatalogServiceCategoryStores listCatalogCategoryStores(
            CatalogServiceCategoryStoresQuery query) {
        return service.catalogCategoryStores(query);
    }
}
