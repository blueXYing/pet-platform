package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantCommandBindingEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffReadEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffScopeEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MerchantStaffMapper {
    void setTimeZoneUtc();
    void setLockWaitTimeout2Seconds();
    MerchantStaffScopeEntity selectOwnedScope(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("ownerUserId") long ownerUserId);
    MerchantStaffScopeEntity lockOwnedScope(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("ownerUserId") long ownerUserId);
    Long lockApplication(@Param("merchantId") long merchantId);
    List<MerchantStaffReadEntity> listAllStaff(@Param("storeId") long storeId);
    MerchantStaffReadEntity selectStaff(@Param("staffId") long staffId);
    MerchantStaffReadEntity lockStaff(@Param("staffId") long staffId);
    int insertStaff(@Param("staffId") long staffId, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("staffName") String staffName,
        @Param("phone") String phone, @Param("employmentStatus") String employmentStatus,
        @Param("serviceEnabled") int serviceEnabled, @Param("now") LocalDateTime now);
    int updateStaff(@Param("staffId") long staffId, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("staffName") String staffName,
        @Param("phone") String phone, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);
    int enableStaff(@Param("staffId") long staffId, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);
    int insertAudit(@Param("id") long id, @Param("actorId") long actorId,
        @Param("action") String action, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("staffId") long staffId,
        @Param("requestKey") byte[] requestKey, @Param("requestId") byte[] requestId,
        @Param("traceId") String traceId, @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus, @Param("fromEnabled") Integer fromEnabled,
        @Param("toEnabled") int toEnabled, @Param("fromVersion") Long fromVersion,
        @Param("toVersion") long toVersion, @Param("now") LocalDateTime now);
    int insertBinding(@Param("id") long id, @Param("requestKey") byte[] requestKey,
        @Param("canonicalVersion") String canonicalVersion, @Param("paramsSha256") String paramsSha256,
        @Param("paramsCanonical") byte[] paramsCanonical, @Param("traceId") String traceId);
    MerchantCommandBindingEntity selectBindingForUpdate(@Param("requestKey") byte[] requestKey);
    int markBindingSucceeded(@Param("requestKey") byte[] requestKey,
        @Param("receiptJson") String receiptJson);
}
