package com.petplatform.thirdparty.api;

import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

/**
 * Rechecks the current merchant material, task claimant and authorization facts. Implementations
 * throw on denial. The private-asset service invokes this while holding its grant transaction, so
 * implementations must join the same DataSource transaction.
 */
@FunctionalInterface
public interface PrivateAssetReadAuthorizer {
  ReadAuthorizationProof authorize(ReadAuthorizationRequest request);
}
