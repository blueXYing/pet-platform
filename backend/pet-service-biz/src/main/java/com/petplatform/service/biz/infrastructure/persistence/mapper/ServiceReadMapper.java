package com.petplatform.service.biz.infrastructure.persistence.mapper;

import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceItemReadEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** service_item read statements. SQL is kept in ServiceReadMapper.xml. */
public interface ServiceReadMapper {

    ServiceItemReadEntity selectServiceById(@Param("serviceId") long serviceId);

    List<ServiceItemReadEntity> selectActiveStoreServices(
            @Param("storeId") long storeId, @Param("limit") int limit, @Param("offset") int offset);

    long countActiveStoreServices(@Param("storeId") long storeId);
}
