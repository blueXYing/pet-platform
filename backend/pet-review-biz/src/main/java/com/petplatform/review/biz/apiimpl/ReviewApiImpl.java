package com.petplatform.review.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.api.command.ReviewCommandApi;
import com.petplatform.review.api.query.ReviewQueryApi;
import com.petplatform.review.biz.application.ReviewAppealPorts;
import com.petplatform.review.biz.application.ReviewService;
import com.petplatform.review.biz.infrastructure.persistence.ReviewStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local assembly of the review kernel (HTTP contract 10 §3.14 + internal 07 §7.7/§14 + appeal
 * contract 56 / REV-002). The persistence store is constructed here, inside pet-review-biz, so
 * no external module ever touches this module's persistence classes (ARCH-002; same shape as
 * OrderQueryApiImpl). The REV-002 appeal faces additionally take the store guard and the
 * port-side authority; without them (pet.review.appeal.enabled off) every appeal call fails
 * closed instead of degrading.
 */
public final class ReviewApiImpl implements ReviewQueryApi, ReviewCommandApi {
    private final ReviewService service;

    public ReviewApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, OrderQueryApi orders) {
        this(dataSource, ids, orders, Clock.systemUTC());
    }

    public ReviewApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, OrderQueryApi orders,
            Clock clock) {
        this(dataSource, ids, orders, clock, null, null);
    }

    /** REV-002 assembly: guard + appeal authority are supplied by the boot appeal slice. */
    public ReviewApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, OrderQueryApi orders,
            Clock clock, ScheduleCapacityGuardApi guard, ReviewAppealPorts appealAuthority) {
        this.service = new ReviewService(
                new ReviewStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids is required")),
                Objects.requireNonNull(orders, "orders is required"),
                Objects.requireNonNull(clock, "clock is required"),
                guard, appealAuthority);
    }

    @Override
    public ReviewEligibilityDTO checkEligibility(ReviewEligibilityQuery query) {
        return service.checkEligibility(query);
    }

    @Override
    public ReviewCreateResult create(ReviewCreateCommand command) {
        return service.create(command);
    }

    @Override
    public CreationOutcome createWithOutcome(ReviewCreateCommand command) {
        return service.createWithOutcome(command);
    }

    // ------------------------------ REV-002 appeal faces (contract 56)

    @Override
    public ReviewAppealResult appeal(ReviewAppealCommand command) {
        return service.appeal(command);
    }

    @Override
    public AppealOutcome appealWithOutcome(ReviewAppealCommand command) {
        return service.appealWithOutcome(command);
    }

    @Override
    public ReviewAppealDecisionResult decide(ReviewAppealDecisionCommand command) {
        return service.decide(command);
    }

    @Override
    public ReviewPage listStoreReviews(StoreReviewListQuery query) {
        return service.listStoreReviews(query);
    }

    @Override
    public ReviewDetail getStoreReview(StoreReviewGetQuery query) {
        return service.getStoreReview(query);
    }

    @Override
    public AppealPage listAppeals(AppealListQuery query) {
        return service.listAppeals(query);
    }

    @Override
    public AppealDetail getAppeal(AppealGetQuery query) {
        return service.getAppeal(query);
    }
}
