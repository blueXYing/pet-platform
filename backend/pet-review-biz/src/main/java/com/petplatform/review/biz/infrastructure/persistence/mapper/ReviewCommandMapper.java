package com.petplatform.review.biz.infrastructure.persistence.mapper;

import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewCommandBindingEntity;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewRowEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * REVIEW command statements; all production SQL lives in ReviewCommandMapper.xml and binds
 * with #{} only (AGENTS persistence ruling). The idempotency statements ride the shared 14号
 * command_idempotency table; the review statements ride the 06号 review table.
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
}
