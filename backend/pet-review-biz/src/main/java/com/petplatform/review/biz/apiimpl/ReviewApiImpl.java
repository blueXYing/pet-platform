package com.petplatform.review.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.api.command.ReviewCommandApi;
import com.petplatform.review.api.query.ReviewQueryApi;
import com.petplatform.review.biz.application.ReviewService;
import com.petplatform.review.biz.infrastructure.persistence.ReviewStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local assembly of the REV-001 review kernel (HTTP contract 10 §3.14 + internal 07 §7.7/§14).
 * The persistence store is constructed here, inside pet-review-biz, so no external module ever
 * touches this module's persistence classes (ARCH-002; same shape as OrderQueryApiImpl).
 */
public final class ReviewApiImpl implements ReviewQueryApi, ReviewCommandApi {
    private final ReviewService service;

    public ReviewApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, OrderQueryApi orders) {
        this(dataSource, ids, orders, Clock.systemUTC());
    }

    public ReviewApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, OrderQueryApi orders,
            Clock clock) {
        this.service = new ReviewService(
                new ReviewStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids is required")),
                Objects.requireNonNull(orders, "orders is required"),
                Objects.requireNonNull(clock, "clock is required"));
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
}
