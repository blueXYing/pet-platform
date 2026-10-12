package com.petplatform.review.biz.infrastructure.persistence.mapper;

import com.petplatform.review.biz.infrastructure.persistence.entity.AppealListRowEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewAppealRowEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewCommandBindingEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewListRowEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewRowEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * REVIEW command statements; all production SQL lives in ReviewCommandMapper.xml and binds
 * with #{} only (AGENTS persistence ruling). The idempotency statements ride the shared 14号
 * command_idempotency table; the review/appeal statements ride the 06号 review /
 * review_appeal tables (REV-002 appeal slice included; zero schema change).
 */
public interface ReviewCommandMapper {

    int setTimeZoneUtc();

    int setLockWaitTimeout2Seconds();

    ReviewCommandBindingEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion, @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    int markBindingSucceeded(@Param("requestKey") String requestKey, @Param("receiptJson") String receiptJson);

    ReviewRowEntity selectReviewByOrderIdForUpdate(@Param("orderId") long orderId);

    int insertReview(@Param("id") long id, @Param("orderId") long orderId,
            @Param("userId") long userId, @Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("serviceId") long serviceId,
            @Param("storeScore") BigDecimal storeScore, @Param("serviceScore") BigDecimal serviceScore,
            @Param("staffScore") BigDecimal staffScore, @Param("compositeScore") BigDecimal compositeScore,
            @Param("scoreIncluded") boolean scoreIncluded, @Param("content") String content,
            @Param("now") LocalDateTime now);

    // ------------------------------ REV-002 appeal (06号 §10 review_appeal)

    ReviewRowEntity selectReviewByIdForUpdate(@Param("reviewId") long reviewId);

    ReviewRowEntity selectReviewById(@Param("reviewId") long reviewId);

    ReviewAppealRowEntity selectAppealByReviewIdForUpdate(@Param("reviewId") long reviewId);

    ReviewAppealRowEntity selectAppealByReviewId(@Param("reviewId") long reviewId);

    ReviewAppealRowEntity selectAppealByIdForUpdate(@Param("appealId") long appealId);

    ReviewAppealRowEntity selectAppealById(@Param("appealId") long appealId);

    int insertAppeal(@Param("id") long id, @Param("reviewId") long reviewId,
            @Param("merchantId") long merchantId, @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    /** CAS on SUBMITTED only: a decided appeal is final (56号 §4). */
    int decideAppeal(@Param("appealId") long appealId, @Param("status") String status,
            @Param("decisionReason") String decisionReason, @Param("decidedBy") long decidedBy,
            @Param("now") LocalDateTime now);

    /** APPROVED hides the violating review from public display (56号 §4). */
    int hideReview(@Param("reviewId") long reviewId, @Param("now") LocalDateTime now);

    long countStoreReviews(@Param("merchantId") long merchantId, @Param("storeId") long storeId);

    List<ReviewListRowEntity> pageStoreReviews(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("offset") long offset,
            @Param("limit") int limit);

    long countAppeals(@Param("status") String status);

    List<AppealListRowEntity> pageAppeals(@Param("status") String status,
            @Param("offset") long offset, @Param("limit") int limit);
}
