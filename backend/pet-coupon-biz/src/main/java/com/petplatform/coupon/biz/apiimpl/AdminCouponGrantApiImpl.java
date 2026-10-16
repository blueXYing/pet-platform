package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponGrantApi;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CouponPort;
import com.petplatform.coupon.biz.application.CouponGrantService;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the contract-65 §4.3 coupon grant face. Boot wires the kernel with
 * the module-local authorization port (same shape as the contract-65 template impl); the
 * assembly switch is pet.coupon.grant.enabled, default off.
 */
public final class AdminCouponGrantApiImpl implements AdminCouponGrantApi {

    private final CouponGrantService service;

    public AdminCouponGrantApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            CouponPort authorization, Clock clock) {
        this.service = new CouponGrantService(
                new com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore(
                        Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public GrantReceipt grant(GrantCouponCommand command) {
        return service.grant(command);
    }
}
