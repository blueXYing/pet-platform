package com.petplatform.points.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.dto.PointsBalanceDTO;
import com.petplatform.points.api.dto.PointsLedgerDTO;
import com.petplatform.points.api.query.PointsQueryApi.PointsBalanceQuery;
import com.petplatform.points.api.query.PointsQueryApi.PointsLedgerQuery;
import com.petplatform.points.biz.infrastructure.persistence.PointsQueryStore;
import com.petplatform.points.biz.infrastructure.persistence.entity.PointsLedgerRowEntity;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * C-end points balance + ledger read model (CCR-C006-COUPON-POINTS-READ-001 P1). V1 points only
 * accrue and get clawed back: this service exposes no earn/clawback path and reads are always
 * scoped to the QueryContext subject. The ledger keeps its fixed created_at DESC ordering.
 */
public final class PointsQueryService {

    /** The five V1 biz types (schema 06 section 9); anything else is rejected, not displayed. */
    private static final Set<String> BIZ_TYPES =
            Set.of("SIGN_IN", "INVITE", "TASK", "ORDER_REWARD", "REFUND_CLAWBACK");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final PointsQueryStore store;

    public PointsQueryService(PointsQueryStore store) {
        this.store = Objects.requireNonNull(store, "store is required");
    }

    public PointsBalanceDTO getBalance(PointsBalanceQuery query) {
        Objects.requireNonNull(query, "query is required");
        Long balance = store.read(mapper -> mapper.selectBalance(subject(query.context())));
        long value = balance == null ? 0L : balance;
        if (value < 0) {
            throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分余额非法");
        }
        return new PointsBalanceDTO(Long.toString(value));
    }

    public PageResult<PointsLedgerDTO> queryLedger(PointsLedgerQuery query) {
        Objects.requireNonNull(query, "query is required");
        long user = subject(query.context());
        int page = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? 20 : query.pageSize();
        long total = store.read(mapper -> mapper.countLedger(user));
        List<PointsLedgerDTO> items;
        if (total == 0) {
            items = List.of();
        } else {
            items = store.read(mapper ->
                            mapper.selectLedgerPage(user, pageSize, (page - 1) * pageSize))
                    .stream().map(PointsQueryService::project).toList();
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    private static long subject(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        return IDS.fromApi(context.operatorId());
    }

    private static PointsLedgerDTO project(PointsLedgerRowEntity row) {
        if (!BIZ_TYPES.contains(row.getBizType()) || row.getDelta() == null
                || row.getDelta() == 0L || row.getBalanceAfter() == null || row.getBalanceAfter() < 0) {
            throw new ApiException(CommonApiCodes.INTERNAL_ERROR, "积分流水非法");
        }
        return new PointsLedgerDTO(
                IDS.toApi(row.getId()),
                row.getBizType(),
                Long.toString(row.getDelta()),
                Long.toString(row.getBalanceAfter()),
                row.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
}
