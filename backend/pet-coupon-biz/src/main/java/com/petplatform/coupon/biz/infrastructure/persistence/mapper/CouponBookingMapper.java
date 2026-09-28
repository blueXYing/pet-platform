package com.petplatform.coupon.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Coupon exposure rows locked in the booking transaction. */
public interface CouponBookingMapper {
    List<Long> lockInstanceByOrder(@Param("orderId") long orderId);
    List<Long> lockLedgerByOrder(@Param("orderId") long orderId);
}
