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

    /**
     * HTTP10 §3.3.3 C catalog keyword search candidates: every ACTIVE service whose service or
     * category name matches the pre-escaped LIKE term, fixed merchant/store/service ascending
     * order (the store catalog ordering discipline, applied across stores). Eligibility is not
     * a SQL concern; the caller evaluates the merchant facts per distinct pair.
     */
    java.util.List<ServiceItemReadEntity> selectActiveServiceCandidates(@Param("keyword") String keyword);
}
