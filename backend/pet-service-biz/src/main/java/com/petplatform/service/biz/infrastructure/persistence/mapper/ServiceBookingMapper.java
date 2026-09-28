package com.petplatform.service.biz.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Current booking service row and its separately read category display name. */
public interface ServiceBookingMapper {
    List<Map<String, Object>> lockService(@Param("serviceId") long serviceId);
    String categoryName(@Param("categoryId") long categoryId);
}
