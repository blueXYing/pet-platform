package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantApplicationStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantApplicationEntity;
import java.util.Objects;
import java.util.Set;

/** Strict SQL29 joined-chain reader used by agreement eligibility in the same merchant snapshot. */
public final class PersistentApplicationReviewFactsReader implements ApplicationReviewFactsReader {
  private static final Set<String> STATUSES = Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
  private final MerchantApplicationStore store;

  public PersistentApplicationReviewFactsReader(MerchantApplicationStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public PersistentApplicationReviewFactsReader(
      javax.sql.DataSource source, com.petplatform.common.SnowflakeIdGenerator ids) {
    this(new MerchantApplicationStore(source, ids));
  }

  @Override
  public Facts read(long merchantId) {
    if (merchantId <= 0) unavailable("merchant identity is invalid");
    MerchantApplicationEntity row =
        store.joinCurrentTransaction(mapper -> mapper.selectEligibilityByMerchant(merchantId));
    if (row == null
        || row.getStatus() == null
        || !STATUSES.contains(row.getStatus())
        || row.getId() == null
        || row.getId() <= 0
        || row.getReservedMerchantId() == null
        || row.getReservedMerchantId() != merchantId
        || row.getOwnerUserId() == null
        || row.getOwnerUserId() <= 0
        || row.getVersion() == null
        || row.getVersion() < 0) {
      unavailable("application review facts are missing or unknown");
    }
    if ("APPROVED".equals(row.getStatus())
        && (row.getCurrentDecisionId() == null
            || !"APPROVE".equals(row.getCurrentDecisionType())
            || row.getReviewAuditId() == null
            || row.getReviewedAt() == null
            || row.getSubmittedRevisionId() == null
            || !Objects.equals(row.getCurrentRevisionId(), row.getSubmittedRevisionId())
            || !"VERIFIED".equals(row.getSubjectVerificationStatus())
            || row.getCurrentCreditClaimId() == null
            || row.getCurrentIdentityClaimId() == null)) {
      unavailable("approved application review chain is damaged");
    }
    if ("APPROVED".equals(row.getStatus()) && !Boolean.TRUE.equals(row.getChainValid())) {
      unavailable("approved application joined chain is damaged");
    }
    return new Facts(row.getStatus());
  }

  private static void unavailable(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }
}
