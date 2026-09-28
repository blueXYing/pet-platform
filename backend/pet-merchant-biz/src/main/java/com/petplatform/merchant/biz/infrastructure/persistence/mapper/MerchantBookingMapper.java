package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Merchant-owned booking eligibility locks. */
public interface MerchantBookingMapper {
    List<Map<String, Object>> lockStore(@Param("storeId") long storeId);
    List<Long> lockApplicationAnchors(@Param("merchantId") long merchantId);
    List<Long> lockAgreementAcceptances(@Param("merchantId") long merchantId);
}
