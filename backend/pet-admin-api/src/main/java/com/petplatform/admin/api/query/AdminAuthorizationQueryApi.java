package com.petplatform.admin.api.query;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;

/** Authoritative current action and resource-scope check for trusted local domain callers. */
@FunctionalInterface
public interface AdminAuthorizationQueryApi {
  AdminActionDecision check(AdminActionCheckQuery query);

  /**
   * Fail-closed source-compatible extension for collection entry checks. Implementations must not
   * represent a collection using a fabricated resource identifier.
   */
  default AdminActionDecision checkCollection(AdminCollectionActionCheckQuery query) {
    throw new UnsupportedOperationException("collection authorization is unavailable");
  }
}
