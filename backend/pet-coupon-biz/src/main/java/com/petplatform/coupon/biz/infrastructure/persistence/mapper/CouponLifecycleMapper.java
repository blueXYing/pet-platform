package com.petplatform.coupon.biz.infrastructure.persistence.mapper;

import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponLifecycleInstanceEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * Coupon lifecycle write statements (contract 61); SQL lives in CouponLifecycleMapper.xml and
 * joins the caller's guarded transaction — no transaction is started here. Ledger uniqueness
 * (uk_coupon_ledger_request) is the replay fence; every mutation is a guarded row-level CAS.
 */
public interface CouponLifecycleMapper {

    void setUtcTimeZone();

    void setLockWaitTimeout();

    /** Locking read of one instance with its template facts (FOR UPDATE, freeze-time decision). */
    CouponLifecycleInstanceEntity lockInstance(@Param("id") long id);

    /** Anti-divergence count of any instance rows already bound to the order (freeze precheck). */
    long countInstancesByOrder(@Param("orderId") long orderId);

    /** Freeze CAS: AVAILABLE + own + unexpired -> FROZEN bound to the order. */
    int freezeInstance(@Param("id") long id, @Param("userId") long userId,
            @Param("orderId") long orderId, @Param("freezeExpireAt") LocalDateTime freezeExpireAt,
            @Param("now") LocalDateTime now);

    /** Locking read of the order's bound instance (consume/release/restore entry). */
    CouponLifecycleInstanceEntity lockInstanceByOrder(@Param("orderId") long orderId);

    /** Consume CAS: the order's FROZEN instance -> USED with the proven channel paidAt. */
    int consumeInstance(@Param("id") long id, @Param("orderId") long orderId,
            @Param("usedAt") LocalDateTime usedAt, @Param("now") LocalDateTime now);

    /** Release CAS: the order's FROZEN instance -> AVAILABLE/EXPIRED by remaining validity. */
    int releaseInstance(@Param("id") long id, @Param("orderId") long orderId,
            @Param("closedAt") LocalDateTime closedAt, @Param("now") LocalDateTime now);

    /** Landing status after release (AVAILABLE when still valid, EXPIRED otherwise). */
    String selectStatus(@Param("id") long id);

    /** Restore CAS (refund FULL): the order's USED instance -> AVAILABLE with 24h extension. */
    int restoreInstance(@Param("id") long id, @Param("orderId") long orderId,
            @Param("newExpireAt") LocalDateTime newExpireAt, @Param("now") LocalDateTime now);

    /** Ledger append; uk_coupon_ledger_request(requestId) is the idempotency fence. */
    int insertLedger(@Param("id") long id, @Param("couponInstanceId") long couponInstanceId,
            @Param("userId") long userId, @Param("orderId") Long orderId, @Param("action") String action,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
            @Param("requestId") String requestId, @Param("remark") String remark,
            @Param("now") LocalDateTime now);
}
