package com.petplatform.admin.biz.domain.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class AdminPermissionEvaluator {
  private AdminPermissionEvaluator() {}

  /**
   * This slice has no deployed domain actions. Own session queries require valid identity, not
   * invented grants.
   */
  public static final Set<String> DEPLOYED_ACTIONS = Set.of();

  public static List<String> evaluate(
      boolean superAdmin, Collection<String> grants, Set<String> deployed) {
    return (superAdmin ? deployed.stream() : grants.stream().filter(deployed::contains))
        .distinct()
        .sorted()
        .toList();
  }
}
