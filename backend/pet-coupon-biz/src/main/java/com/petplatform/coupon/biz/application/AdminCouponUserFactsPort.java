package com.petplatform.coupon.biz.application;

/**
 * Boot adapter resolves the user owner's read-only account existence fact for the contract-65
 * grant face (hardening ruling 2026-10-19 "继续扩展CCR"): coupon biz never depends on another
 * biz, so the kernel sees only this port and boot binds it to the user owner's public
 * read-only query API (UserActiveStatusApi). Read-only by construction — the grant face never
 * mutates user state and proves existence (any account status) rather than usability.
 * Fail-closed: an unavailable owner answer is a 503, never an unconditional grant.
 */
public final class AdminCouponUserFactsPort {

    private AdminCouponUserFactsPort() {}

    @FunctionalInterface
    public interface Port {
        /**
         * true only when the user_account row exists (any status); lexical ID checks stay
         * with the caller. The operator id rides along so the owner read carries the real
         * authenticated subject.
         */
        boolean exists(long userId, String operatorId);
    }

    /** Distinct type so boot adapters can implement the kernel port without FQN noise. */
    public interface CouponPort extends Port {}
}
