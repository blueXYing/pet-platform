package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantCommandBindingEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreProfileRowEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** Store profile statements (27号 §6.4); scope/agreement/binding reuse the module tables. */
public interface MerchantStoreProfileMapper {
    void setTimeZoneUtc();
    void setLockWaitTimeout2Seconds();

    MerchantStoreProfileRowEntity.Scope selectOwnedScope(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("ownerUserId") long ownerUserId);
    MerchantStoreProfileRowEntity.Scope lockOwnedScope(@Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("ownerUserId") long ownerUserId);
    Long lockApplication(@Param("merchantId") long merchantId);

    MerchantStoreProfileRowEntity selectProfile(@Param("storeId") long storeId);
    MerchantStoreProfileRowEntity lockProfile(@Param("storeId") long storeId);
    int insertProfile(@Param("storeId") long storeId, @Param("merchantId") long merchantId,
        @Param("intro") String intro, @Param("announcement") String announcement,
        @Param("businessHours") String businessHours, @Param("promoLevel") Integer promoLevel,
        @Param("promoTitle") String promoTitle, @Param("promoText") String promoText,
        @Param("photoAssetIds") String photoAssetIds, @Param("now") LocalDateTime now);
    int updateProfile(@Param("storeId") long storeId, @Param("merchantId") long merchantId,
        @Param("intro") String intro, @Param("announcement") String announcement,
        @Param("businessHours") String businessHours, @Param("promoLevel") Integer promoLevel,
        @Param("promoTitle") String promoTitle, @Param("promoText") String promoText,
        @Param("photoAssetIds") String photoAssetIds, @Param("expectedVersion") long expectedVersion,
        @Param("now") LocalDateTime now);

    int insertAudit(@Param("id") long id, @Param("actorId") long actorId,
        @Param("action") String action, @Param("merchantId") long merchantId,
        @Param("storeId") long storeId, @Param("requestKey") byte[] requestKey,
        @Param("requestId") byte[] requestId, @Param("traceId") String traceId,
        @Param("fromVersion") Long fromVersion, @Param("toVersion") long toVersion,
        @Param("changedFields") String changedFields, @Param("now") LocalDateTime now);

    int insertBinding(@Param("id") long id, @Param("requestKey") byte[] requestKey,
        @Param("canonicalVersion") String canonicalVersion, @Param("paramsSha256") String paramsSha256,
        @Param("paramsCanonical") byte[] paramsCanonical, @Param("traceId") String traceId);
    MerchantCommandBindingEntity selectBindingForUpdate(@Param("requestKey") byte[] requestKey);
    int markBindingSucceeded(@Param("requestKey") byte[] requestKey,
        @Param("receiptJson") String receiptJson);
}
