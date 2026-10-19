package com.petplatform.aftersale.api.query;

import java.util.List;

/**
 * Read-only cross-domain fact port: whether the caller-named subject still has open
 * (processing) aftersale cases that block account deletion (contract 66 / PRD 5.1.29
 * precheck; CCR-USR-DELETION-001). "Open" is this domain's own active-case fact:
 * active_flag=1 with status in {PENDING, PROCESSING, WAITING_SUPPLEMENT} — the same active
 * set the DisplayOrderStatus AFTERSALE tab projects. Pure fact read — no session subject,
 * no case fields, no enumeration surface; unavailable facts fail closed at the consumer
 * (503), never read as "nothing blocks".
 */
public interface AfterSaleDeletionFactsApi {

    /** True when the named user holds at least one open aftersale case. */
    boolean userHasOpenCase(UserDeletionFactQuery query);

    /** True when any named store holds at least one open aftersale case (owner stores). */
    boolean storesHaveOpenCase(StoreDeletionFactQuery query);

    /** userId is the canonical decimal string form of the aftersale_case.user_id being asked about. */
    record UserDeletionFactQuery(String userId) {}

    /** storeIds are canonical decimal aftersale_case.store_id strings; empty means no stores. */
    record StoreDeletionFactQuery(List<String> storeIds) {
        public StoreDeletionFactQuery { storeIds = List.copyOf(storeIds); }
    }
}
