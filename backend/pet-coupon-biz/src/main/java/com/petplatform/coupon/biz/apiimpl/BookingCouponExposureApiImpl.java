package com.petplatform.coupon.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.coupon.api.query.BookingCouponExposureApi;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponMybatis;
import com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponBookingMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;
/** Reads only this owner's tables. Every future exposure writer must acquire the same store guard. */
public final class BookingCouponExposureApiImpl implements BookingCouponExposureApi {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final CouponBookingMapper mapper;
    public BookingCouponExposureApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source=Objects.requireNonNull(source); this.guard=Objects.requireNonNull(guard);
        this.mapper=CouponMybatis.template(source).getMapper(CouponBookingMapper.class);
    }
    @Override public void requireNoCoupon(String orderId,String storeId,QueryContext context) {
        long value=new DecimalPublicIdCodec().fromApi(orderId);
        guard.requireHeld(storeId,source);
        try {
            if (!mapper.lockInstanceByOrder(value).isEmpty()) throw unavailable();
            if (!mapper.lockLedgerByOrder(value).isEmpty()) throw unavailable();
        } catch (RuntimeException failure) { throw unavailable(); }
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking coupon exposure requires reconciliation");
    }
}
