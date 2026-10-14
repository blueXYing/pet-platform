package com.petplatform.points.biz.apiimpl;

import com.petplatform.common.QueryContext;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInStatusView;
import com.petplatform.points.api.dto.PointsTaskTypes.TasksView;
import com.petplatform.points.api.query.PointsTaskQueryApi;
import com.petplatform.points.biz.application.PointsTaskService;
import com.petplatform.points.biz.domain.service.PointsTaskFacts;
import com.petplatform.points.biz.infrastructure.persistence.PointsTaskStore;
import com.petplatform.common.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-58 read side (status, tasks, config). */
public final class PointsTaskQueryApiImpl implements PointsTaskQueryApi {
    private final PointsTaskService service;

    public PointsTaskQueryApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            PointsTaskFacts facts, Clock clock) {
        this.service = new PointsTaskService(
                new PointsTaskStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(facts, "facts are required"),
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public SignInStatusView signInStatus(SignInStatusQuery query) {
        return service.signInStatus(query.context());
    }

    @Override
    public TasksView tasks(TasksQuery query) {
        return service.tasks(query.context());
    }

    @Override
    public ConfigView config(ConfigQuery query) {
        return service.config();
    }
}
