package com.petplatform.order.biz.infrastructure.persistence.mapper;

import com.petplatform.order.biz.infrastructure.persistence.entity.AdminOrderRowEntity;
import com.petplatform.order.biz.infrastructure.persistence.entity.AdminRescheduleEntity;
import com.petplatform.order.biz.infrastructure.persistence.entity.AdminStatusLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only contract-63 platform order statements; SQL lives in AdminOrderQueryMapper.xml.
 * Everything binds with #{}, never string substitution; the optional displayStatus selects one
 * of the fixed XML truth-table branches (the service validates the ten-value vocabulary first).
 */
public interface AdminOrderQueryMapper {

    long countOrders(
            @Param("displayStatus") String displayStatus,
            @Param("paymentStatus") String paymentStatus,
            @Param("merchantId") Long merchantId,
            @Param("storeId") Long storeId,
            @Param("orderNo") Long orderNo,
            @Param("merchantKeyword") String merchantKeyword,
            @Param("createdFrom") java.time.LocalDateTime createdFrom,
            @Param("createdTo") java.time.LocalDateTime createdTo,
            @Param("rescheduledOnly") boolean rescheduledOnly);

    List<AdminOrderRowEntity> selectOrderPage(
            @Param("displayStatus") String displayStatus,
            @Param("paymentStatus") String paymentStatus,
            @Param("merchantId") Long merchantId,
            @Param("storeId") Long storeId,
            @Param("orderNo") Long orderNo,
            @Param("merchantKeyword") String merchantKeyword,
            @Param("createdFrom") java.time.LocalDateTime createdFrom,
            @Param("createdTo") java.time.LocalDateTime createdTo,
            @Param("rescheduledOnly") boolean rescheduledOnly,
            @Param("limit") int limit,
            @Param("offset") long offset);

    AdminOrderRowEntity selectOrderById(@Param("id") long id);

    List<AdminRescheduleEntity> selectReschedules(@Param("orderId") long orderId);

    List<AdminStatusLogEntity> selectStatusLogs(@Param("orderId") long orderId);
}
