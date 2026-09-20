package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;

public interface MerchantApplicationQueryApi {
  MerchantApplicationResult getCurrent(CurrentMerchantApplicationQuery query);

  MerchantApplicationPage listForReview(MerchantApplicationReviewListQuery query);

  MerchantApplicationReviewDetail getForReview(MerchantApplicationReviewQuery query);

  MerchantApplicationScopeFact getScope(MerchantApplicationScopeQuery query);

  MerchantApplicationEligibilityFact getEligibility(MerchantApplicationEligibilityQuery query);
}
