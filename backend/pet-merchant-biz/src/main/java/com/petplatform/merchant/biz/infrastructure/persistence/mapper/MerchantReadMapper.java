package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantEligibilityBaseEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffReadEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreReadEntity;
import org.apache.ibatis.annotations.Param;

/** Merchant-owned read statements. SQL is kept in MerchantReadMapper.xml. */
public interface MerchantReadMapper {

    MerchantStoreReadEntity selectOwnedStore(
            @Param("storeId") long storeId,
            @Param("ownerUserId") long ownerUserId
    );

    MerchantEligibilityBaseEntity selectOwnedEligibilityBase(
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("ownerUserId") long ownerUserId
    );

    /** SVC-D5 display eligibility base; same projection without the ownership predicate. */
    MerchantEligibilityBaseEntity selectDisplayEligibilityBase(
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId
    );

    java.util.List<MerchantStoreReadEntity> selectOwnedStores(
            @Param("ownerUserId") long ownerUserId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countOwnedStores(@Param("ownerUserId") long ownerUserId);

    MerchantStaffReadEntity selectOwnedStaff(
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("staffId") long staffId,
            @Param("ownerUserId") long ownerUserId
    );

    /**
     * STR-D6 display candidates: every merchant/store pair with the compat city fact, no status or
     * ownership predicate — statuses are evaluated by the approved policy (unknown values must
     * fail closed as 503, never silently hidden by a SQL filter) and city filtering happens after
     * the integrity check. Ordered merchantId, storeId numeric ascending.
     */
    java.util.List<com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreDisplayRowEntity>
            selectDisplayStoreCandidates();

    /** STR-D6 single-store display row (null when the store does not exist). */
    com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreDisplayRowEntity
            selectDisplayStore(@Param("storeId") long storeId);
}
