package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSettlementFact;

/**
 * Read-only ORDER settlement facts for trusted SYSTEM callers (PAYMENT settlement kernel).
 * Never mutates ORDER state and never requires a store capacity guard.
 */
public interface OrderSettlementFactsApi {
    OrderSettlementFact readForSettlement(String orderId, QueryContext context);
}
