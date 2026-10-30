package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantFeaturedStoreFactRowEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Featured-card store facts read statements. SQL is kept in MerchantFeaturedStoreFactsMapper.xml. */
public interface MerchantFeaturedStoreFactsMapper {

    /**
     * Contract 74 §3.4 (B-CARD-02): the display conjunction's status columns plus the compat city
     * and owner-maintained profile facts for the requested store ids, one query per call. The
     * contact phone and coordinates are deliberately not projected — the featured card carries no
     * PII. Visibility filtering stays in the application layer, exactly like the store catalog.
     */
    List<MerchantFeaturedStoreFactRowEntity> selectFeaturedStoreFactRows(
            @Param("storeIds") List<Long> storeIds);
}
