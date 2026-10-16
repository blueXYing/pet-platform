package com.petplatform.points.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;

/**
 * Read-only platform points ledger face for the single operator (contract 65 §5, PRD 6.5
 * 积分流水, HTTP10 §5.8 drafted route, A-005). The caller is always the ADMIN_WEB operator
 * carried by {@link QueryContext}; {@code authorization} re-proves the current session, the
 * points.ledger.read action code and the ALL data scope inside the points kernel on every
 * call. V1 points only accrue and get clawed back (SSOT §10) — this API offers no adjustment,
 * freeze or clawback write path, and ledger rows are immutable facts.
 */
public interface AdminPointsQueryApi {

    /** One page of ledger rows, fixed order created_at DESC, id DESC. */
    PageResult<AdminLedgerRow> queryLedger(AdminLedgerListQuery query);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /** PRD 6.5 five fields plus the order linkage; delta is signed (positive accrual). */
    record AdminLedgerRow(
            String ledgerId, String userId, String bizType, String delta, String balanceAfter,
            String orderId, OffsetDateTime createdAt) {}

    /**
     * @param bizType SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK or null (no filter)
     * @param userId canonical decimal String or null (no filter)
     */
    record AdminLedgerListQuery(
            String userId, String bizType, OffsetDateTime createdFrom, OffsetDateTime createdTo,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}
}
