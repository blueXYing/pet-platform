package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi;
import com.petplatform.coupon.api.command.MerchantCouponApi;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.application.MerchantCouponAdminService;
import com.petplatform.coupon.biz.application.MerchantCouponAdmissionGate;
import com.petplatform.coupon.biz.application.MerchantCouponService;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the merchant coupon face and its operator review (contract 79
 * sections 3 and 4). Boot supplies only the api-module ports (owner admission, authorization,
 * merchant display names); the MyBatis store stays inside the coupon module (ARCH-002).
 * Member types are qualified on purpose: both interfaces declare Page/Item/Receipt.
 */
public final class MerchantCouponApiImpl implements MerchantCouponApi, MerchantCouponAdminApi {

    private final MerchantCouponService merchant;
    private final MerchantCouponAdminService admin;

    public MerchantCouponApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            AdminCouponAuthorizationPort.CouponPort authorization,
            MerchantQueryApi merchants, Clock clock) {
        CouponAdminStore store = new CouponAdminStore(
                Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids are required"));
        Clock resolvedClock = clock == null ? Clock.systemUTC() : clock;
        this.merchant = new MerchantCouponService(store,
                new MerchantCouponAdmissionGate(admissions), resolvedClock);
        this.admin = new MerchantCouponAdminService(store, authorization, merchants, resolvedClock);
    }

    // ------------------------------------------------------------------ merchant face

    @Override
    public MerchantCouponApi.Page list(MerchantCouponApi.ListQuery query) {
        return merchant.list(query);
    }

    @Override
    public MerchantCouponApi.Item get(MerchantCouponApi.GetQuery query) {
        return merchant.get(query);
    }

    @Override
    public MerchantCouponApi.Overview overview(MerchantCouponApi.ScopeQuery query) {
        return merchant.overview(query);
    }

    @Override
    public MerchantCouponApi.Receipt create(MerchantCouponApi.CreateCommand command) {
        return merchant.create(command);
    }

    @Override
    public MerchantCouponApi.Receipt update(MerchantCouponApi.UpdateCommand command) {
        return merchant.update(command);
    }

    @Override
    public MerchantCouponApi.Receipt submit(MerchantCouponApi.StatusCommand command) {
        return merchant.submit(command);
    }

    @Override
    public MerchantCouponApi.Receipt cancel(MerchantCouponApi.StatusCommand command) {
        return merchant.cancel(command);
    }

    // ------------------------------------------------------------------ operator review

    @Override
    public MerchantCouponAdminApi.Page list(MerchantCouponAdminApi.ListQuery query) {
        return admin.list(query);
    }

    @Override
    public MerchantCouponAdminApi.Item get(MerchantCouponAdminApi.GetQuery query) {
        return admin.get(query);
    }

    @Override
    public MerchantCouponAdminApi.Receipt approve(MerchantCouponAdminApi.ReviewCommand command) {
        return admin.approve(command);
    }

    @Override
    public MerchantCouponAdminApi.Receipt reject(MerchantCouponAdminApi.ReviewCommand command) {
        return admin.reject(command);
    }

    @Override
    public MerchantCouponAdminApi.Receipt pause(MerchantCouponAdminApi.ReviewCommand command) {
        return admin.pause(command);
    }

    @Override
    public MerchantCouponAdminApi.Receipt end(MerchantCouponAdminApi.ReviewCommand command) {
        return admin.end(command);
    }
}
