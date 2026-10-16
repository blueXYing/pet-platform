package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponVoidApi;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CouponPort;
import com.petplatform.coupon.biz.application.CouponVoidService;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the contract-65 §4.4 coupon void face (user ruling 2026-10-16,
 * doc 36). Boot wires the kernel with the module-local authorization port (same shape as the
 * contract-65 grant impl); the assembly switch is pet.coupon.void.enabled, default off.
 */
public final class AdminCouponVoidApiImpl implements AdminCouponVoidApi {

    private final CouponVoidService service;

    public AdminCouponVoidApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            CouponPort authorization, Clock clock) {
        this.service = new CouponVoidService(
                new com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore(
                        Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public VoidReceipt voidInstance(VoidCouponCommand command) {
        return service.voidInstance(command);
    }
}
