package com.petplatform.coupon.api.command;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** C-side active coupon claim (contract 72 §5.2). */
public interface CouponClaimApi {

    ClaimReceipt claim(ClaimCommand command);

    record ClaimCommand(String campaignId, String userId, String requestId, String traceId) {}

    record ClaimReceipt(
            String campaignId,
            String claimId,
            String couponInstanceId,
            BigDecimal amountOff,
            OffsetDateTime expireAt,
            OffsetDateTime claimedAt) {}
}
