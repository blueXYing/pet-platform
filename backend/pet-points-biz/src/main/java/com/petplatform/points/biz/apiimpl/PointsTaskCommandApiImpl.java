package com.petplatform.points.biz.apiimpl;

import com.petplatform.common.CommandContext;
import com.petplatform.points.api.command.PointsTaskCommandApi;
import com.petplatform.points.api.dto.PointsTaskTypes.AwardClaimReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand;
import com.petplatform.points.biz.application.PointsTaskService;
import com.petplatform.points.biz.domain.service.PointsTaskFacts;
import com.petplatform.points.biz.infrastructure.persistence.PointsTaskStore;
import com.petplatform.common.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-58 earn commands (sign-in, referral, claims, config). */
public final class PointsTaskCommandApiImpl implements PointsTaskCommandApi {
    private final PointsTaskService service;

    public PointsTaskCommandApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            PointsTaskFacts facts, Clock clock) {
        this.service = new PointsTaskService(
                new PointsTaskStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(facts, "facts are required"),
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public SignInReceipt signIn(SignInCommand command) {
        return service.signIn(command);
    }

    @Override
    public ReferralBindingReceipt bindReferral(BindReferralCommand command) {
        return service.bindReferral(command);
    }

    @Override
    public AwardClaimReceipt claimReward(ClaimRewardCommand command) {
        return service.claimReward(command);
    }

    @Override
    public ConfigView updateConfig(UpdateConfigCommand command) {
        return service.updateConfig(command);
    }
}
