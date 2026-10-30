package com.petplatform.order.biz.infrastructure.persistence.mapper;

import com.petplatform.order.biz.infrastructure.persistence.entity.ServiceCatalogSalesCountEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Public service-catalog sales facts statements (74号 §6.2 / CCR-SVC-CARD-001 §3.1); SQL lives
 * in ServiceCatalogSalesFactsMapper.xml. Both statements are batch-only grouped counts over
 * pet_order, scoped by the caller-named store/service coordinate and projected to id + count.
 */
public interface ServiceCatalogSalesFactsMapper {

    /** Effective orders per store in the half-open UTC instant window [from, to). */
    List<ServiceCatalogSalesCountEntity> storeMonthlySold(@Param("storeIds") List<Long> storeIds,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** All-time effective orders per service. */
    List<ServiceCatalogSalesCountEntity> serviceSold(@Param("serviceIds") List<Long> serviceIds);
}
