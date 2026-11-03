package com.petplatform.order.biz.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.biz.infrastructure.persistence.entity.ServiceCatalogSalesCountEntity;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed unit coverage for the sales-facts aggregate mapping (74号 §6.2 /
 * CCR-SVC-CARD-001 §3.1): a grouped row whose count is unavailable is damaged facts, never
 * a fabricated zero.
 */
class ServiceCatalogSalesFactsServiceTest {

    @Test
    void groupedCountIsReturnedWhenPresent() {
        assertEquals(3L, ServiceCatalogSalesFactsService.count(row(3L)));
    }

    @Test
    void nullGroupedCountFailsClosedAsDependencyUnavailable() {
        ApiException failure = assertThrows(ApiException.class,
                () -> ServiceCatalogSalesFactsService.count(row(null)));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
    }

    private static ServiceCatalogSalesCountEntity row(Long orderCount) {
        ServiceCatalogSalesCountEntity row = new ServiceCatalogSalesCountEntity();
        row.setId(42L);
        row.setOrderCount(orderCount);
        return row;
    }
}
