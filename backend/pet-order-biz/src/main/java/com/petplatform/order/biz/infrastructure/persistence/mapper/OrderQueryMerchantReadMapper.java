package com.petplatform.order.biz.infrastructure.persistence.mapper;

import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerAggregate;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerNoteRow;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerPet;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerWindowCount;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.ServiceOrderRowEntity;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.ServiceTopRow;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.StatsTotals;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.TrendRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contracts 69/70 derived read statements (resources/mapper/OrderQueryMerchantReadMapper.xml).
 * Every statement is store-scoped and read-only; the service runs them after the schedule
 * guard + OWNER re-proof inside the shared read transaction.
 */
public interface OrderQueryMerchantReadMapper {

    long countStoreCustomers(@Param("merchantId") long merchantId, @Param("storeId") long storeId);

    List<CustomerAggregate> pageStoreCustomers(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("limit") int limit, @Param("offset") long offset);

    List<CustomerAggregate> scanStoreCustomers(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("limit") int limit);

    CustomerAggregate storeCustomerAggregate(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("userId") long userId);

    List<CustomerPet> storeCustomerPets(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("userId") long userId, @Param("limit") int limit);

    /** The store's single internal note row for one derived customer; null before first save. */
    CustomerNoteRow storeCustomerNote(@Param("storeId") long storeId, @Param("userId") long userId);

    StatsTotals statsWindowTotals(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    CustomerWindowCount statsWindowCustomers(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    Long statsNewCustomers(@Param("merchantId") long merchantId, @Param("storeId") long storeId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<ServiceTopRow> statsServiceTop(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to, @Param("limit") int limit);

    List<TrendRow> statsRevenueTrend(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    long statsServiceOrderTotal(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("serviceId") long serviceId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<ServiceOrderRowEntity> statsServiceOrders(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("serviceId") long serviceId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("limit") int limit, @Param("offset") long offset);

    String statsServiceLatestName(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("serviceId") long serviceId);

    long countStoreCustomersByOrderSuffix(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("suffix") String suffix);

    List<CustomerAggregate> pageStoreCustomersByOrderSuffix(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("suffix") String suffix,
            @Param("limit") int limit, @Param("offset") long offset);
}
