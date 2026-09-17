package com.petplatform.admin.biz.domain.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class AdminPermissionEvaluator {
  private AdminPermissionEvaluator() {}

  /** Actions with an implemented server-side enforcement point. */
  public static final Set<String> DEPLOYED_ACTIONS = Set.of("merchant.application.read", "merchant.application.decide", "merchant.identity.reveal");

  public static List<String> evaluate(
      boolean superAdmin, Collection<String> grants, Set<String> deployed) {
    return (superAdmin ? deployed.stream() : grants.stream().filter(deployed::contains))
        .distinct()
        .sorted()
        .toList();
  }
}
