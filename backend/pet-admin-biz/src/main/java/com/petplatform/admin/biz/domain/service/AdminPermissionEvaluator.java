package com.petplatform.admin.biz.domain.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class AdminPermissionEvaluator {
  private AdminPermissionEvaluator() {}

  /**
   * Actions with an implemented server-side enforcement point. The three service review codes are
   * registered on behalf of the ADM-001 service write slice (proposal SVCW-D7, approved
   * 2026-09-22); full RBAC registration stays with the AUTH owner. The force-offline intent is
   * spelled service.force.offline to satisfy the AUTH action-code lexicon (lowercase dotted
   * segments, AdminActionCheckQuery) — implementation-phase spelling correction, PR-disclosed.
   * The two review-appeal codes are registered on behalf of the REV-002 review appeal slice
   * (CCR-REVIEW-APPEAL-001, contract 56): review.appeal.read / review.appeal.decide. The two
   * points-task codes are registered on behalf of the contract-58 earn slice (CCR-POINTS-TASK-001,
   * 32号 ruling: 签到与拉新积分量后台可配置): points.task.config.read / points.task.config.write.
   * The four trade codes are registered on behalf of the contract-63 platform trade read slice
   * (ADM-002/A-003, AUTH-001 RBAC proposal matrix rows 7/8): order.read, order.status-log.read,
   * order.verification-log.read, refund.read — GET-only reads; no trade write action exists.
   * The three governance codes are registered on behalf of the contract-64 platform governance
   * read slice (ADM-001 gap / A-002 phase 2): merchant.read (AUTH-001 matrix row 3) and
   * schedule.read / merchant.staff.read (PRD 6.2.5/6.2.6 read-only supervision, SSOT §29/§30;
   * proposed codes disclosed in contract 64 — no governance write action exists).
   * The four disposal codes are registered on behalf of the contract-63 v0.2 disposal slice
   * (user ruling 2026-10-15, doc 33; AUTH-001 matrix rows 4/7/8): refund.channel.query,
   * refund.retry, order.abnormal.close, merchant.disable. Proposal spellings
   * "refund.channel-query" / "order.abnormal-close" use hyphens; the AUTH action-code lexicon
   * (AdminActionCheckQuery) only admits lowercase dotted segments, so the deployed spelling is
   * the dotted correction — same implementation-phase spelling correction as
   * service.force.offline, PR-disclosed. The ten ops-console codes are registered on behalf of
   * the contract-65 operations governance slice (A-005, matrix rows 21/13/14/15/23):
   * encyclopedia.read, encyclopedia.manage, encyclopedia.publish, breed.read, breed.manage
   * (row 21, dotted spellings as proposed), coupon.template.read, coupon.template.manage,
   * coupon.instance.read (rows 13/14 proposal spellings "coupon-template.*" /
   * "coupon-instance.*" dotted-corrected like refund.channel.query), points.ledger.read
   * (row 15 family, ledger supervision read) and system.parameter.read (row 23 proposal
   * "system-parameter.read" dotted-corrected). Row 14 void/return and row 23 configure stay
   * unregistered — no SSOT authorization exists for instance-status writes or runtime
   * configuration (CCR-ADM-A005-OPS-001). The eleventh ops-console code coupon.instance.grant
   * is registered on behalf of the contract-65 §4.3 grant channel slice (user ruling
   * 2026-10-16, CCR-CPN-GRANT-001): one AVAILABLE instance per terminal requestId from an
   * ACTIVE template; row 14 void/return stay unregistered.
   */
  public static final Set<String> DEPLOYED_ACTIONS =
      Set.of("merchant.application.read", "merchant.application.decide",
          "merchant.identity.reveal", "service.review.read", "service.review.decide",
          "service.force.offline", "aftersale.read", "aftersale.handle", "aftersale.decide",
          "review.appeal.read", "review.appeal.decide",
          "points.task.config.read", "points.task.config.write",
          "order.read", "order.status-log.read", "order.verification-log.read", "refund.read",
          "merchant.read", "schedule.read", "merchant.staff.read",
          "refund.channel.query", "refund.retry", "order.abnormal.close", "merchant.disable",
          "encyclopedia.read", "encyclopedia.manage", "encyclopedia.publish",
          "breed.read", "breed.manage",
          "coupon.template.read", "coupon.template.manage", "coupon.instance.read",
          "coupon.instance.grant",
          "points.ledger.read", "system.parameter.read");

  public static List<String> evaluate(
      boolean superAdmin, Collection<String> grants, Set<String> deployed) {
    return (superAdmin ? deployed.stream() : grants.stream().filter(deployed::contains))
        .distinct()
        .sorted()
        .toList();
  }
}
