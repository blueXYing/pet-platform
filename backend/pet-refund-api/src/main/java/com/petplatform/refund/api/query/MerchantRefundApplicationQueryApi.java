package com.petplatform.refund.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only merchant refund-application surfaces (contract 57, HTTP10 §4.4 draft): the
 * caller's own store coordinate only. The OWNER merchant coordinate (merchantId+storeId)
 * is re-proven per call inside the store-guard read transaction by the owner-read port;
 * list and detail then filter by both keys so a foreign, absent or non-owned store answers
 * the same COMMON_FORBIDDEN (anti-enumeration) and cross-store rows never leak. The list
 * is fixed to the pending-decision backlog ({@code PENDING_MERCHANT}); the single read
 * accepts any status of the owned store so the decided outcome stays reviewable. The
 * buyer's free-text reason stays sealed (contract 49 cipher; CCR-M-REFUND-VIEW-001 §3.2 was
 * NOT approved, so no decryption path exists) and no buyer id is exposed. CCR-M-REFUND-VIEW-001
 * §3.1/§3.3 (approved 2026-11-06) add only the nullable customer nickname, the user domain's
 * server-masked phone ({@code 138****0100}, never the raw number) and the order-time service
 * snapshot name, plus the timestamp-derived read-only timeline. This API offers no write
 * path — decisions stay on {@code RefundApplicationCommandApi.decide}.
 */
public interface MerchantRefundApplicationQueryApi {

    /** One page of the store's pending refund applications, newest first (created_at DESC, id DESC). */
    PageResult<Summary> listStoreApplications(StoreApplicationListQuery query);

    /** One owned application by id; unknown or foreign ids read as COMMON_FORBIDDEN. */
    Summary readStoreApplication(StoreApplicationQuery query);

    /**
     * Wire-neutral merchant summary; ids/amounts/instants render as wire strings per contract 57.
     * customerNickname/customerPhoneMasked/serviceName are nullable display facts (absent or
     * unusable facts read null, never a placeholder). timeline is never null: the stages that
     * have a persisted fact, ascending by occurredAt (contract 57 renders it on the detail only).
     */
    record Summary(String applicationId, String applicationNo, String orderId, String status,
            String applicationVersion, String reasonCode, BigDecimal refundAmount,
            OffsetDateTime createdAt, OffsetDateTime merchantDeadline, OffsetDateTime decidedAt,
            String decisionId, String refundOrderId, String customerNickname,
            String customerPhoneMasked, String serviceName, List<TimelineEntry> timeline) {
        public Summary {
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
        }
    }

    /**
     * CCR-M-REFUND-VIEW-001 §3.3 read-only timeline entry. stage is one of APPLIED,
     * MERCHANT_DEADLINE, DECIDED, REFUND_ORDER_CREATED; each entry exists only because its
     * persisted timestamp exists (never inferred, never a DisplayOrderStatus).
     */
    record TimelineEntry(String stage, OffsetDateTime occurredAt) {}

    /** merchantId/storeId are canonical decimal strings; the subject is the session user. */
    record StoreApplicationListQuery(String merchantId, String storeId, int page, int pageSize,
            QueryContext context) {}

    /** applicationId is a canonical decimal string; same owner discipline as the list. */
    record StoreApplicationQuery(String merchantId, String storeId, String applicationId,
            QueryContext context) {}
}
