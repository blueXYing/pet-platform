package com.petplatform.order.biz.apiimpl;

import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi;
import com.petplatform.order.biz.application.ServiceCatalogSalesFactsService;
import com.petplatform.order.biz.infrastructure.persistence.ServiceCatalogSalesFactsStore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the order-owned public sales facts port (74号 §6.2 /
 * CCR-SVC-CARD-001 §3.1). Read-only and subject-free; the composing C read face supplies the
 * store/service ids from its own visibility-filtered page.
 */
public final class ServiceCatalogSalesFactsApiImpl implements ServiceCatalogSalesFactsApi {
    private final ServiceCatalogSalesFactsService service;

    public ServiceCatalogSalesFactsApiImpl(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public ServiceCatalogSalesFactsApiImpl(DataSource dataSource, Clock clock) {
        this.service = new ServiceCatalogSalesFactsService(
                new ServiceCatalogSalesFactsStore(
                        Objects.requireNonNull(dataSource, "dataSource is required")),
                clock == null ? Clock.systemUTC() : clock);
    }

    @Override
    public List<StoreMonthlySold> storeMonthlySold(StoreMonthlySoldQuery query) {
        return service.storeMonthlySold(query);
    }

    @Override
    public List<ServiceSold> serviceSold(ServiceSoldQuery query) {
        return service.serviceSold(query);
    }
}
