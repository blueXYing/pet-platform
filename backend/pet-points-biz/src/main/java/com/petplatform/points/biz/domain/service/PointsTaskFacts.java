package com.petplatform.points.biz.domain.service;

import java.time.OffsetDateTime;

/**
 * Cross-domain read facts the points task kernel needs (CCR-POINTS-TASK-001 P3). Implemented in
 * pet-boot by adapters over the order/user query APIs — never by reading another module's
 * persistence from here (ARCH-002). All methods are pure reads.
 */
public interface PointsTaskFacts {

  /**
   * First successful payment time of the user's own orders, null when the user never completed a
   * payment (07号 §7.1 amendment {@code firstPaidAt}). Drives the conservative referral
   * condition R2 (binding requires a never-ordered invitee) and R3 (reward requires a payment
   * strictly after the binding), plus the FIRST_ORDER milestone M1.
   */
  OffsetDateTime firstPaidAt(String userId);

  /** Whether the internal user id exists and is enabled (R1 invite-code validity). */
  boolean userEnabled(String userId);

  /** Current nickname of the user, empty when absent (PROFILE_COMPLETE milestone M1). */
  String userNickname(String userId);

  /** Test/adapter convenience: a facts source with fixed values. */
  static PointsTaskFixed fixed() {
    return new PointsTaskFixed();
  }

  /** Mutable fixed facts used by kernel tests and boot adapters. */
  final class PointsTaskFixed implements PointsTaskFacts {
    public OffsetDateTime firstPaidAt;
    public boolean enabled = true;
    public String nickname = "";

    @Override
    public OffsetDateTime firstPaidAt(String userId) {
      return firstPaidAt;
    }

    @Override
    public boolean userEnabled(String userId) {
      return enabled;
    }

    @Override
    public String userNickname(String userId) {
      return nickname == null ? "" : nickname;
    }
  }
}
