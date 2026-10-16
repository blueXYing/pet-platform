package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponCommandApi;
import com.petplatform.coupon.api.query.AdminCouponQueryApi;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Port;
import com.petplatform.coupon.biz.application.AdminCouponService;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the contract-65 coupon admin face. Boot wires the kernel with the
 * module-local authorization port (same shape as the contract-63 order impl).
 */
public final class AdminCouponApiImpl implements AdminCouponQueryApi, AdminCouponCommandApi {

    private final AdminCouponService service;

    public AdminCouponApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, Port authorization,
            Clock clock) {
        this.service = new AdminCouponService(
                new com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore(
                        Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public PageResult<AdminCouponQueryApi.AdminTemplateSummary> listTemplates(
            AdminCouponQueryApi.AdminTemplateListQuery query) {
        return service.listTemplates(query);
    }

    @Override
    public AdminCouponQueryApi.AdminTemplateDetail getTemplate(
            AdminCouponQueryApi.AdminTemplateQuery query) {
        return service.getTemplate(query);
    }

    @Override
    public AdminCouponQueryApi.TrialCalculation trialCalculation(
            AdminCouponQueryApi.TrialCalculationQuery query) {
        return service.trialCalculation(query);
    }

    @Override
    public PageResult<AdminCouponQueryApi.AdminInstanceSummary> listInstances(
            AdminCouponQueryApi.AdminInstanceListQuery query) {
        return service.listInstances(query);
    }

    @Override
    public AdminCouponQueryApi.AdminInstanceDetail getInstance(
            AdminCouponQueryApi.AdminInstanceQuery query) {
        return service.getInstance(query);
    }

    @Override
    public AdminCouponCommandApi.TemplateMutation createTemplate(
            AdminCouponCommandApi.CreateTemplateCommand command) {
        return service.createTemplate(command);
    }

    @Override
    public AdminCouponCommandApi.TemplateMutation updateTemplate(
            AdminCouponCommandApi.UpdateTemplateCommand command) {
        return service.updateTemplate(command);
    }

    @Override
    public AdminCouponCommandApi.TemplateMutation enableTemplate(
            AdminCouponCommandApi.TemplateStatusCommand command) {
        return service.enableTemplate(command);
    }

    @Override
    public AdminCouponCommandApi.TemplateMutation offlineTemplate(
            AdminCouponCommandApi.TemplateStatusCommand command) {
        return service.offlineTemplate(command);
    }
}
