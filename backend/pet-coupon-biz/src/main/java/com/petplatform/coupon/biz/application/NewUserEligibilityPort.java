package com.petplatform.coupon.biz.application;

/**
 * Contract 72 new-user gate. Boot adapts the user/order owners: a user is a new user when the
 * account exists and has no paid orders (PRD 6.4.2 recommended variant). Unavailable facts must
 * fail closed; an absent row is not a new user.
 */
public interface NewUserEligibilityPort {
    boolean isNewUser(long userId, String traceId);
}
