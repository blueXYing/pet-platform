package com.petplatform.order.biz.infrastructure.persistence.mapper;
import org.apache.ibatis.annotations.Param;
public interface OrderCredentialMapper {
 Location locate(@Param("id")long id);
 class Location {public Long id,userId,merchantId,storeId,reservationId;}
}
