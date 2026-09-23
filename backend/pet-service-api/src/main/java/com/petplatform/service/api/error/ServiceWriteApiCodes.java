package com.petplatform.service.api.error;

/**
 * Stable service write-side codes (12号 §12 additions, approved 2026-09-22). HTTP mapping lives in
 * the terminal adapter: SERVICE_STATE_NOT_ALLOWED -> 409, SERVICE_REVIEW_REASON_REQUIRED -> 400.
 */
public final class ServiceWriteApiCodes {
    private ServiceWriteApiCodes() {}

    /** State-machine violation or non-operable merchant facts: edit on ACTIVE/REVIEWING, submit
     * from a non-editable status, offline on non-ACTIVE, decision on non-REVIEWING, force-offline
     * on non-ACTIVE, or a DENIED/LIMITED merchant admission fact (proposal SVCW-D5/D8). */
    public static final String SERVICE_STATE_NOT_ALLOWED = "SERVICE_STATE_NOT_ALLOWED";

    /** REJECT without the mandatory 10-500 character opinion (proposal SVCW-D8). */
    public static final String SERVICE_REVIEW_REASON_REQUIRED = "SERVICE_REVIEW_REASON_REQUIRED";
}
