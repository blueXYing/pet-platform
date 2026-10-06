package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.coupon.api.dto.CouponInstanceDTO;
import com.petplatform.coupon.api.query.CouponQueryApi;
import com.petplatform.coupon.biz.application.CouponQueryService;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the CCR-C006 P1 C-end coupon read surface (read-only). */
public final class CouponQueryApiImpl implements CouponQueryApi {
    private final CouponQueryService service;

    public CouponQueryApiImpl(DataSource dataSource) {
        this.service = new CouponQueryService(
                new CouponQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")));
    }

    @Override
    public PageResult<CouponInstanceDTO> listMyCoupons(MyCouponListQuery query) {
        return service.listMyCoupons(query);
    }

    @Override
    public CouponInstanceDTO getMyCoupon(MyCouponQuery query) {
        return service.getMyCoupon(query);
    }
}
