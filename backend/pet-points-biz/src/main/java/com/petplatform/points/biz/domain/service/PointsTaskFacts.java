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

  /**
   * Identity of the user's earliest paid order, null when the user never completed a payment
   * (contract 60 §7, CCR-POINTS-ORDER-001 P1). Pure read over the caller's own order facts —
   * the boot adapter pages {@code OrderQueryApi.listMyOrders} with the same subject, so no
   * order-module change and no cross-user surface opens. Feeds the order binding of the
   * FIRST_ORDER award so contract 60 §7 refund clawback can find the reward by order.
   */
  FirstPaidOrder firstPaidOrder(String userId);

  /** First paid order identity: canonical decimal orderId string plus the paid instant. */
  record FirstPaidOrder(String orderId, OffsetDateTime paidAt) {}

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
    public FirstPaidOrder firstPaidOrder;
    public boolean enabled = true;
    public String nickname = "";

    @Override
    public OffsetDateTime firstPaidAt(String userId) {
      return firstPaidAt;
    }

    @Override
    public FirstPaidOrder firstPaidOrder(String userId) {
      return firstPaidOrder;
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
