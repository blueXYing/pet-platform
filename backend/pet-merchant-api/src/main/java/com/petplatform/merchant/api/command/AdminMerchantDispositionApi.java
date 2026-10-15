package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/**
 * Contract-63 v0.2 merchant governance (HTTP10 §5.6 draft; user ruling 2026-10-15, doc 33): the
 * operator disable action. SSOT §15 semantics: 立即下线 — stop NEW orders (booking eligibility
 * already fails closed for non-ACTIVE merchants), while EXISTING orders keep fulfilling and
 * existing refunds/aftersales keep processing (the order/refund kernels already admit OFFLINE
 * merchants for in-flight work). The existing merchant status vocabulary is reused (ACTIVE →
 * OFFLINE; no new status); in-flight orders never block the action. The kernel re-proves the
 * ADMIN_WEB session, the {@code merchant.disable} action and the resource scope inside the same
 * commit that writes the append-only merchant_governance_action audit row bound to the terminal
 * requestId.
 */
public interface AdminMerchantDispositionApi {

    DisableReceipt disable(DisableCommand command);

    record DisableCommand(
            String merchantId,
            String reason,
            CommandContext context,
            AdminAuthorization authorization) {}

    /** Trusted final-authorization input re-checked inside the merchant kernel per call. */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record DisableReceipt(
            String merchantId,
            String status,
            OffsetDateTime occurredAt) {}
}
