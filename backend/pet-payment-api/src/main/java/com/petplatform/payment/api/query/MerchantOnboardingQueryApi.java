package com.petplatform.payment.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAuditView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingDefaultsView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingPage;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingView;
import com.petplatform.payment.api.dto.OnboardingTypes.ListOnboardingsQuery;
import java.util.List;

/** Read-only query API for merchant acquiring onboarding (Contract 81 §4); every view is masked. */
public interface MerchantOnboardingQueryApi {
    OnboardingDefaultsView defaults(String merchantId, QueryContext context);
    OnboardingView getByMerchantId(String merchantId, QueryContext context);
    OnboardingPage list(ListOnboardingsQuery query);
    List<OnboardingAuditView> listAudits(String merchantId, QueryContext context);
}
