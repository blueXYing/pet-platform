package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;

public interface MerchantApplicationQueryApi {
  MerchantApplicationResult getCurrent(CurrentMerchantApplicationQuery query);

  OwnerApplicationDetail getCurrentDetail(CurrentMerchantApplicationQuery query);

  MerchantApplicationPage listForReview(MerchantApplicationReviewListQuery query);

  MerchantApplicationReviewDetail getForReview(MerchantApplicationReviewQuery query);

  MerchantApplicationScopeFact getScope(MerchantApplicationScopeQuery query);

  MerchantPrivateMaterialAccessFact provePrivateMaterialAccess(
      MerchantPrivateMaterialAccessQuery query);

  MerchantApplicationEligibilityFact getEligibility(MerchantApplicationEligibilityQuery query);
}
