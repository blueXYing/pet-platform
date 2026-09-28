package com.petplatform.coupon.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.coupon.api.query.BookingCouponExposureApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
/** Reads only this owner's tables. Every future exposure writer must acquire the same store guard. */
public final class BookingCouponExposureApiImpl implements BookingCouponExposureApi {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final JdbcTemplate jdbc;
    public BookingCouponExposureApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source=Objects.requireNonNull(source); this.guard=Objects.requireNonNull(guard);
        this.jdbc=new JdbcTemplate(source);
    }
    @Override public void requireNoCoupon(String orderId,String storeId,QueryContext context) {
        long value=new DecimalPublicIdCodec().fromApi(orderId);
        guard.requireHeld(storeId,source);
        try {
            if (!jdbc.query("SELECT id FROM coupon_instance WHERE order_id=? LIMIT 1 FOR UPDATE", (rs,n)->rs.getLong(1), value).isEmpty()) throw unavailable();
            if (!jdbc.query("SELECT id FROM coupon_ledger WHERE order_id=? LIMIT 1 FOR UPDATE", (rs,n)->rs.getLong(1), value).isEmpty()) throw unavailable();
        } catch (RuntimeException failure) { throw unavailable(); }
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking coupon exposure requires reconciliation");
    }
}
