package com.petplatform.user.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.user.api.dto.PaymentIdentity;

/** First USER payment dispatch only; SYSTEM reconciliation uses the original PAYMENT binding. */
public interface PaymentIdentityApi {
    PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
            String storeId, QueryContext context);
}
