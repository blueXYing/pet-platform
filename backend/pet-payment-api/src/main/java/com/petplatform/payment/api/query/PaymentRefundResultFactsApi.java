package com.petplatform.payment.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentRefundResultFact;

/** Only a guarded, persisted and signature-verified terminal result authorizes REFUND completion. */
public interface PaymentRefundResultFactsApi {
    PaymentRefundResultFact requireVerified(String refundOrderId, String refundNo,
            String paymentId, String storeId, QueryContext context);
}
