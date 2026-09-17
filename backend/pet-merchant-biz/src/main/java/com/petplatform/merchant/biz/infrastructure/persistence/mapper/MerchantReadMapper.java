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

    MerchantStaffReadEntity selectOwnedStaff(
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("staffId") long staffId,
            @Param("ownerUserId") long ownerUserId
    );
}
