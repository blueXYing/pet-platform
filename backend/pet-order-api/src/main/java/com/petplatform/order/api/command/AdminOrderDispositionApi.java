package com.petplatform.order.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/**
 * Contract-63 v0.2 platform order disposal (HTTP10 §5.5 draft; user ruling 2026-10-15, doc 33):
 * the operator abnormal close. Fixed semantics: 异常关闭 + 全额退款 — never a substitute merchant
 * confirm (运营代接单 stays forbidden). The kernel walks the EXISTING order state machine
 * (order_stage → CANCELED with the ADMIN_ABNORMAL_CLOSE fact; no new order status), enforces the
 * same unfulfilled-order guards as the merchant-reject close (UNVERIFIED, no open aftersale or
 * refund application, no existing refund), creates the full refund through the refund kernel and
 * re-proves the ADMIN_WEB session, the {@code order.abnormal.close} action and the resource scope
 * inside the commit that writes the append-only audit row bound to the terminal requestId.
 */
public interface AdminOrderDispositionApi {

    AbnormalCloseReceipt abnormalClose(AbnormalCloseCommand command);

    record AbnormalCloseCommand(
            String orderId,
            String reasonCode,
            String reason,
            CommandContext context,
            AdminAuthorization authorization) {}

    /** Trusted final-authorization input re-checked inside the order kernel per call. */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record AbnormalCloseReceipt(
            String orderId,
            String orderStage,
            String cancelReason,
            String refundOrderId,
            OffsetDateTime occurredAt) {}
}
