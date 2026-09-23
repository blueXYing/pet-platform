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
   */
  public static final Set<String> DEPLOYED_ACTIONS =
      Set.of("merchant.application.read", "merchant.application.decide",
          "merchant.identity.reveal", "service.review.read", "service.review.decide",
          "service.force.offline");

  public static List<String> evaluate(
      boolean superAdmin, Collection<String> grants, Set<String> deployed) {
    return (superAdmin ? deployed.stream() : grants.stream().filter(deployed::contains))
        .distinct()
        .sorted()
        .toList();
  }
}
