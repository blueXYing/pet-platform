package com.petplatform.refund.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;

public interface RefundExecutionFactsApi {
    RefundExecutionFact requireForChannel(String refundOrderId, String storeId, QueryContext context);
    RefundSuccessFact requireSucceeded(String refundOrderId, String orderId, String storeId, QueryContext context);
}
