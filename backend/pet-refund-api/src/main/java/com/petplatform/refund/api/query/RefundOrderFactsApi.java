package com.petplatform.refund.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;

/** Requires the caller's writable transaction and store guard, held until commit.
 * Caller has locked its authoritative ORDER/store binding. All refund sources/statuses count.
 * Failure is never represented by NONE. New refund creators must use the same store guard.
 */
public interface RefundOrderFactsApi {
    Fact findByOrder(String orderId, String storeId, QueryContext context);
    record Refund(String refundOrderId, String orderId, String status) {}
    record Fact(List<Refund> refunds) {
        public Fact { refunds = List.copyOf(refunds); }
        public boolean exists() { return !refunds.isEmpty(); }
    }
}
