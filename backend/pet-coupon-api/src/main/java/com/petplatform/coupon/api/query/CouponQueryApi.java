package com.petplatform.coupon.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.dto.CouponInstanceDTO;

/**
 * C-end "my coupons" read surface (CCR-C006-COUPON-POINTS-READ-001 P1, approved 2026-10-06).
 * Read-only: the subject is always the session user carried by {@link QueryContext}; this API
 * offers no issue/freeze/consume/release/refund path. Realizes internal contract 07 section 12.1
 * for the C-side projection; the booking-time AVAILABLE-only view stays drafted until CPN-002.
 */
public interface CouponQueryApi {

    /**
     * Status-bucketed page of the caller's own coupon instances. Only AVAILABLE/USED/EXPIRED are
     * selectable buckets (D2: FROZEN/RISK_FROZEN are never returned by any bucket).
     */
    PageResult<CouponInstanceDTO> listMyCoupons(MyCouponListQuery query);

    /**
     * Single own-coupon projection. A coupon that does not exist or belongs to someone else is
     * reported the same way (COMMON_NOT_FOUND) so the id space cannot be enumerated.
     */
    CouponInstanceDTO getMyCoupon(MyCouponQuery query);

    /** Status bucket (AVAILABLE/USED/EXPIRED); page starts at 1. */
    record MyCouponListQuery(String status, int page, int pageSize, QueryContext context) {}

    /** couponId is the canonical decimal string form of the coupon_instance id. */
    record MyCouponQuery(String couponId, QueryContext context) {}
}
