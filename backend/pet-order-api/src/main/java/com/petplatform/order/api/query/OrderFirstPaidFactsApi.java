package com.petplatform.order.api.query;

import com.petplatform.order.api.dto.OrderFirstPaidFact;

/**
 * Read-only cross-domain fact port: the user's earliest paid order, owned and projected by the
 * order domain (07号 §7.1 amendment, contract 60 §7 / CCR-POINTS-ORDER-001 O(1) closure).
 * Replaces the boot-side {@code listMyOrders} pagination resolution: the read-side projection
 * lives where the fact lives (order), boot adapters only carry it. Pure fact read — no session
 * subject, no order fields beyond the identity and instant, no enumeration surface.
 */
public interface OrderFirstPaidFactsApi {

    /**
     * Earliest paid order of the named user ({@code payment_status='PAID'} with a non-null
     * {@code paid_at}; earliest paid instant, smallest canonical orderId as the deterministic
     * tie-break), null when the user never completed a payment. A malformed userId reads the
     * same way as a user without orders: null, never an error.
     */
    OrderFirstPaidFact firstPaidOrder(UserOrderFactQuery query);

    /** userId is the canonical decimal string form of the pet_order.user_id being asked about. */
    record UserOrderFactQuery(String userId) {}
}
