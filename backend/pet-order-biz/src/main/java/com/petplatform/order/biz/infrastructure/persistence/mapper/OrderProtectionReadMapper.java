package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OrderProtectionReadMapper {
    List<OrderMapperRows.ProtectionOrder> readStoreOrders(@Param("storeId") long storeId);
    List<OrderMapperRows.Assignment> readStoreAssignments(@Param("storeId") long storeId);
    Long findGlobalCurrentOrphan();
}
