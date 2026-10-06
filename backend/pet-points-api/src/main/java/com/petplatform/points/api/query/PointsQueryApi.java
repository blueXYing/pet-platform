package com.petplatform.points.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.dto.PointsBalanceDTO;
import com.petplatform.points.api.dto.PointsLedgerDTO;

/**
 * C-end points balance + ledger read surface (CCR-C006-COUPON-POINTS-READ-001 P1, approved
 * 2026-10-06). V1 points only accrue and get clawed back (never spent); this API is read-only
 * and realizes internal contract 07 section 13.1 verbatim.
 */
public interface PointsQueryApi {

    /** Balance of the session user as a non-negative integer string (BIGINT transport safety). */
    PointsBalanceDTO getBalance(PointsBalanceQuery query);

    /** Ledger page of the session user, fixed ordering created_at DESC (newest first). */
    PageResult<PointsLedgerDTO> queryLedger(PointsLedgerQuery query);

    record PointsBalanceQuery(QueryContext context) {}

    record PointsLedgerQuery(int page, int pageSize, QueryContext context) {}
}
