package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Contract 75 §4 business-agnostic payment center records (CCR-PAY-CENTER-001 stage 1).
 * The collecting merchant is never a command field: PAYMENT always uses its configured platform merchant.
 */
public final class PaymentCenterTypes {
    private PaymentCenterTypes() {}

    /** {@code amount}/{@code expireAt} come from the in-process business owner's own facts, never a terminal body. */
    public record CreateCenterPaymentCommand(CommandContext context, String bizType, String bizNo,
            BigDecimal amount, OffsetDateTime expireAt) {}

    /** A success always carries verified and still valid WeChat parameters. */
    public record CenterPayment(String paymentId, String paymentNo, String bizType, String bizNo,
            String channel, WechatPayParameters wechatPayParameters, OffsetDateTime parameterValidUntil,
            boolean replayed) {
        @Override public String toString() { return "CenterPayment[redacted]"; }
    }

    public record QueryCenterPayment(QueryContext context, String bizType, String bizNo) {}

    /** Current committed facts only; never a success proof (use PaymentCenterFactsApi). */
    public record CenterPaymentView(String paymentId, String paymentNo, String bizType, String bizNo,
            BigDecimal amount, String currency, String status, BigDecimal paidAmount,
            OffsetDateTime paidAt, OffsetDateTime expireAt) {}

    /** Locked authoritative PAYMENT success, read inside the caller's transaction on the same DataSource. */
    public record CenterPaymentSuccessFact(String paymentId, String paymentNo, String bizType, String bizNo,
            String userId, BigDecimal amount, BigDecimal paidAmount, OffsetDateTime paidAt,
            String channelTradeNo, String successEventId, String currency) {}
}
