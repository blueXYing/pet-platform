package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminReservationListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminReservationRow;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminScheduleWindow;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminStaffWindow;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminStaffWindowListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminWindowListQuery;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.ScheduleGovernanceAuthorizationPort;
import com.petplatform.schedule.biz.application.AdminScheduleQueryService;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleAdminViewStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-64 schedule view read face. */
public final class AdminScheduleQueryApiImpl implements AdminScheduleQueryApi {
    private final AdminScheduleQueryService service;

    public AdminScheduleQueryApiImpl(
            DataSource dataSource,
            ScheduleGovernanceAuthorizationPort authorization,
            java.time.Clock clock) {
        this.service = new AdminScheduleQueryService(
                new ScheduleAdminViewStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                clock == null ? java.time.Clock.systemUTC() : clock);
    }

    @Override
    public PageResult<AdminScheduleWindow> listWindows(AdminWindowListQuery query) {
        return service.listWindows(query);
    }

    @Override
    public PageResult<AdminStaffWindow> listStaffWindows(AdminStaffWindowListQuery query) {
        return service.listStaffWindows(query);
    }

    @Override
    public PageResult<AdminReservationRow> listReservations(AdminReservationListQuery query) {
        return service.listReservations(query);
    }
}
