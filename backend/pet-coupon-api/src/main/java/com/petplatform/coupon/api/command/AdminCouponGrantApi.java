package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/**
 * Coupon grant channel for the single operator (contract 65 §4.3, user ruling 2026-10-16):
 * issue one coupon_instance into the user's AVAILABLE bucket from an ACTIVE template. The
 * SSOT §9 covers refund-restore only and grants no further issuance vocabulary, so this face
 * is the deliberate minimum: one user, one template, one instance per terminal requestId.
 * Batch grants, per-user hold caps, frequency controls and total_stock drawdown stay
 * unimplemented (CCR-CPN-GRANT-001). The template must satisfy the frozen 61 §3 precondition
 * ④ usability fact at grant time (status ACTIVE and now within [validStartAt, validEndAt]);
 * otherwise 409. The instance inherits expire_at = template.valid_end_at (no instance-level
 * validity model exists — SQL06); the ISSUE ledger row (request_id 'CPN:GRANT:{instanceId}')
 * is the append-only audit. Idempotency binds inside the coupon kernel on command_idempotency
 * (14号) with same-parameter replay returning the first receipt and any other payload under
 * the same requestId answering 409 IDEMPOTENCY_KEY_CONFLICT.
 */
public interface AdminCouponGrantApi {

    /** Grants one AVAILABLE instance of the template to the user; receipt is replay-stable. */
    GrantReceipt grant(GrantCouponCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /**
     * @param templateId the ACTIVE template to issue from (decimal Snowflake String)
     * @param userId     the recipient user (decimal Snowflake String; existence is not
     *                   proven cross-module — CCR-CPN-GRANT-001)
     */
    record GrantCouponCommand(
            String templateId, String userId,
            CommandContext context, AdminAuthorization authorization) {}

    record GrantReceipt(
            String instanceId, String templateId, String userId, String status,
            OffsetDateTime expireAt, OffsetDateTime occurredAt) {}
}
