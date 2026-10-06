package com.petplatform.coupon.biz.infrastructure.persistence.mapper;

import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponInstanceViewEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** C-end "my coupons" read statements; SQL lives in CouponQueryMapper.xml (CCR-C006 P1). */
public interface CouponQueryMapper {

    long countMine(@Param("userId") long userId, @Param("statuses") List<String> statuses);

    List<CouponInstanceViewEntity> selectMinePage(
            @Param("userId") long userId,
            @Param("statuses") List<String> statuses,
            @Param("limit") int limit,
            @Param("offset") int offset);

    CouponInstanceViewEntity selectMineById(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("statuses") List<String> statuses);
}
