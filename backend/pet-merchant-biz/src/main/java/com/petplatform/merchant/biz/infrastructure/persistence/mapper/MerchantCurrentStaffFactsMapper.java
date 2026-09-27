package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffReadEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreStaffFactsEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MerchantCurrentStaffFactsMapper {
    MerchantStoreStaffFactsEntity lockStore(@Param("storeId") long storeId);
    List<MerchantStaffReadEntity> lockStaff(@Param("storeId") long storeId);
}
