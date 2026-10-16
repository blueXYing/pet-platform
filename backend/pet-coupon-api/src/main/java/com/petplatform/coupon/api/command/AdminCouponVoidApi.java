package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/**
 * Coupon void write action for the single operator (contract 65 §4.4, user ruling 2026-10-16,
 * doc 36 minimal authorization): void one {@code AVAILABLE} coupon_instance. Deliberately
 * narrow — every non-AVAILABLE state is rejected (409): {@code USED} is spent value,
 * {@code FROZEN}/{@code RISK_FROZEN} are locked by an in-flight order (voiding must never
 * break a booking that still holds the coupon) and {@code EXPIRED} is already terminal. The
 * write lands {@code status='VOID'} (new terminal instance state, Schema06 vocabulary note
 * updated and PR-disclosed), appends the VOID ledger row (request_id
 * {@code CPN:VOID:{instanceId}}, uk — one void per instance is structurally guaranteed) and is
 * invisible to every C-end bucket immediately (the contract-10 read side only projects
 * AVAILABLE/USED/EXPIRED). No compensation path exists (CCR-CPN-VOID-001 minimum: void is
 * terminal). Idempotency binds inside the coupon kernel on command_idempotency (14号) with
 * same-parameter replay returning the first receipt and any other payload under the same
 * requestId answering 409 IDEMPOTENCY_KEY_CONFLICT.
 */
public interface AdminCouponVoidApi {

    /** Voids one AVAILABLE instance; the receipt is replay-stable. */
    VoidReceipt voidInstance(VoidCouponCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /**
     * @param instanceId the AVAILABLE instance to void (decimal Snowflake String)
     * @param reason     audit reason, 10..200 code points (ledger remark carries it)
     */
    record VoidCouponCommand(
            String instanceId, String reason,
            CommandContext context, AdminAuthorization authorization) {}

    record VoidReceipt(
            String instanceId, String templateId, String userId, String status,
            OffsetDateTime occurredAt) {}
}
