package com.petplatform.coupon.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Coupon grant channel for the single operator (contract 65 §4.3, user rulings 2026-10-16
 * minimum + 2026-10-19 "继续扩展CCR" hardening batch). Issues coupon_instance rows into the
 * user's AVAILABLE bucket from an ACTIVE template. The SSOT §9 covers refund-restore only
 * and grants no further issuance vocabulary, so every extension below is the industry-
 * minimum reading of the ruling and is disclosed in CCR-CPN-GRANT-001:
 * <ul>
 *   <li>per-user hold cap — AVAILABLE+FROZEN instances of one template stay within the
 *       configured maximum (default 10, pet.coupon.grant.max-per-template); over the cap 409;</li>
 *   <li>grant frequency — the same operator granting the same user the same template twice
 *       inside the 60-second sliding window answers 409 (plain DB counting, no Redis);</li>
 *   <li>total_stock drawdown — a template with total_stock set can only issue while
 *       issued_count is below it (NULL stays unlimited); the drawdown commits in the same
 *       row-locked transaction as the write;</li>
 *   <li>recipient existence — the kernel proves the user_account row exists through the
 *       read-only user-owner port before any write; missing user answers 404 (no ownerless
 *       coupons);</li>
 *   <li>batch grant — one command carries up to 100 userIds; every user binds its own
 *       command_idempotency row and runs its own execution transaction, so a mid-batch
 *       rejection never rolls back users already granted; the receipt lists a per-user
 *       status line each.</li>
 * </ul>
 * The template must satisfy the frozen 61 §3 precondition ④ usability fact at grant time
 * (status ACTIVE and now within [validStartAt, validEndAt]); otherwise 409. The instance
 * inherits expire_at = template.valid_end_at (no instance-level validity model — SQL06);
 * the ISSUE ledger row (request_id 'CPN:GRANT:{instanceId}') is the append-only audit.
 * Idempotency binds inside the coupon kernel on command_idempotency (14号): single grants
 * keep the original template-scoped binding (same-requestId same-parameter replay returns
 * the first receipt, any other payload answers 409 IDEMPOTENCY_KEY_CONFLICT); batch users
 * bind under per-user suffixed keys so replays are independent per user.
 */
public interface AdminCouponGrantApi {

    /** Grants one AVAILABLE instance of the template to the user; receipt is replay-stable. */
    GrantReceipt grant(GrantCouponCommand command);

    /**
     * Grants one AVAILABLE instance per listed user; per-user independent idempotency,
     * guards and transactions — a rejected user never rolls back an already-granted one.
     * Business rejections (404 missing user, 409 guards) become per-user REJECTED lines;
     * authorization or infrastructure failures abort the whole command unchanged.
     */
    BatchGrantReceipt grantBatch(BatchGrantCouponCommand command);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /**
     * @param templateId the ACTIVE template to issue from (decimal Snowflake String)
     * @param userId     the recipient user (decimal Snowflake String; existence is proven
     *                   against the user owner through the boot-wired read port)
     */
    record GrantCouponCommand(
            String templateId, String userId,
            CommandContext context, AdminAuthorization authorization) {}

    /**
     * @param templateId the ACTIVE template to issue from (decimal Snowflake String)
     * @param userIds    distinct recipients, 1..100 entries (decimal Snowflake Strings)
     */
    record BatchGrantCouponCommand(
            String templateId, List<String> userIds,
            CommandContext context, AdminAuthorization authorization) {}

    record GrantReceipt(
            String instanceId, String templateId, String userId, String status,
            OffsetDateTime expireAt, OffsetDateTime occurredAt) {}

    /** One per-user line of the batch receipt. status is GRANTED or REJECTED. */
    record BatchGrantItem(
            String userId, String status, String instanceId,
            OffsetDateTime expireAt, OffsetDateTime occurredAt, String code, String message) {}

    /**
     * @param results one line per requested user, in request order — GRANTED lines carry
     *                the instance facts, REJECTED lines carry code/message
     */
    record BatchGrantReceipt(
            String templateId, List<BatchGrantItem> results,
            int grantedCount, int rejectedCount, OffsetDateTime occurredAt) {}
}
