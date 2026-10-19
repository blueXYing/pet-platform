package com.petplatform.order.api.query;

/**
 * Read-only cross-domain fact port: whether the caller-named subject still has orders that
 * block account deletion (contract 66 / PRD 5.1.29 precheck; CCR-USR-DELETION-001). The
 * blocking truth is the order domain's own DisplayOrderStatus vocabulary restricted to the
 * five PRD-named states — PENDING_PAYMENT, PENDING_CONFIRM, PENDING_SERVICE,
 * REFUND_PENDING_CONFIRM, REFUNDING — projected as fixed XML branches over this module's own
 * pet_order columns (same discipline as the C-004 displayFilter; the AFTERSALE display
 * branch is deliberately not part of this fact: open aftersale cases are owned by the
 * aftersale domain's deletion fact, so one blocker has exactly one owner). Pure fact read —
 * no session subject, no order fields, no enumeration surface; unavailable facts fail
 * closed at the consumer (503), never read as "nothing blocks".
 */
public interface OrderDeletionFactsApi {

    /** True when the named user holds at least one order in a deletion-blocking state. */
    boolean userHasBlockingOrder(UserDeletionFactQuery query);

    /** True when any named store holds at least one deletion-blocking order (owner stores). */
    boolean storesHaveBlockingOrder(StoreDeletionFactQuery query);

    /** userId is the canonical decimal string form of the pet_order.user_id being asked about. */
    record UserDeletionFactQuery(String userId) {}

    /** storeIds are canonical decimal pet_order.store_id strings; empty means no stores. */
    record StoreDeletionFactQuery(java.util.List<String> storeIds) {
        public StoreDeletionFactQuery { storeIds = List.copyOf(storeIds); }
    }
}
