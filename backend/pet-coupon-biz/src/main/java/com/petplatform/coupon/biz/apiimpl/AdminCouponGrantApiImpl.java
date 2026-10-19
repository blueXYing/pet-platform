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
 * the module-local authorization and user-existence ports (same shape as the contract-65
 * template impl); the assembly switch is pet.coupon.grant.enabled, default off, and the
 * hold cap rides pet.coupon.grant.max-per-template (default 10, CCR-CPN-GRANT-001 hardening).
 */
public final class AdminCouponGrantApiImpl implements AdminCouponGrantApi {

    private final CouponGrantService service;

    public AdminCouponGrantApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            CouponPort authorization,
            com.petplatform.coupon.biz.application.AdminCouponUserFactsPort.CouponPort userFacts,
            CouponGrantService.Settings settings, Clock clock) {
        this.service = new CouponGrantService(
                new com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore(
                        Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(userFacts, "userFacts is required"),
                settings == null ? CouponGrantService.Settings.defaults() : settings,
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public GrantReceipt grant(GrantCouponCommand command) {
        return service.grant(command);
    }

    @Override
    public BatchGrantReceipt grantBatch(BatchGrantCouponCommand command) {
        return service.grantBatch(command);
    }
}
