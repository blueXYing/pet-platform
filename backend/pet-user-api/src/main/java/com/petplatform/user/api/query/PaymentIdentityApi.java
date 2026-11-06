package com.petplatform.user.api.query;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.dto.PaymentIdentity;

/** First USER payment dispatch only; SYSTEM reconciliation uses the original PAYMENT binding. */
public interface PaymentIdentityApi {
    PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
            String storeId, QueryContext context);

    /**
     * Contract 75 §4.1: the business-agnostic payment center has no store. Same USER checks as the
     * store variant, serialized by the USER rows locked inside the caller's transaction on the same
     * DataSource. Implementations that do not support it fail closed.
     */
    default PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
            QueryContext context) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "current payment identity unavailable");
    }
}
