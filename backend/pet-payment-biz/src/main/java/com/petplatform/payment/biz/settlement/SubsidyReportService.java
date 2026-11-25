package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportResponse;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportRow;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportSummary;
import com.petplatform.payment.api.query.SubsidyReportQueryApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentReconcileStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.SubsidyGroupRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.SubsidySummaryRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Contract 80 §4.5 / Ruling 40 §3.2 / Ruling 22 strictly read-only platform subsidy payable report.
 * Aggregates {@code settlement_order} rows with {@code platform_subsidy_due > 0.00} by merchant and
 * month ({@code yyyy-MM}). Exposes zero write operations.
 */
public final class SubsidyReportService implements SubsidyReportQueryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentReconcileStore store;

    public SubsidyReportService(DataSource source) {
        this.store = new PaymentReconcileStore(Objects.requireNonNull(source, "source"));
    }

    @Override
    public SubsidyReportResponse querySubsidyReport(SubsidyReportQuery query) {
        if (query == null) {
            throw invalid("missing subsidy report query");
        }
        requireAdminOrSystemQuery(query.context());

        Long merchantId = parseNullableId(query.merchantId());
        if (query.fromDate() != null && query.toDate() != null && query.toDate().isBefore(query.fromDate())) {
            throw invalid("toDate must not be before fromDate");
        }
        LocalDateTime fromAt = query.fromDate() == null ? null : query.fromDate().atStartOfDay();
        LocalDateTime toAtExclusive = query.toDate() == null ? null : query.toDate().plusDays(1).atStartOfDay();

        int page = Math.max(1, query.page());
        int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
        int offset = (page - 1) * size;

        Map<String, Object> params = PaymentFoundationStore.values(
                "merchantId", merchantId,
                "fromAt", fromAt,
                "toAtExclusive", toAtExclusive,
                "limit", size,
                "offset", offset);

        SubsidySummaryRow summaryRow = store.reconcile.selectSubsidyReportSummary(params);
        SubsidyReportSummary summary = summaryRow == null
                ? new SubsidyReportSummary(0L, ZERO, ZERO, ZERO)
                : new SubsidyReportSummary(
                        summaryRow.totalOrderCount,
                        scale2(summaryRow.totalMerchantShare),
                        scale2(summaryRow.totalSplitAmount),
                        scale2(summaryRow.totalSubsidyDue));

        long total = store.reconcile.countSubsidyReportGroups(params);
        List<SubsidyReportRow> items = total == 0 ? List.of()
                : store.reconcile.listSubsidyReportRows(params).stream()
                        .map(SubsidyReportService::toReportRow)
                        .toList();

        return new SubsidyReportResponse(summary, items, total, page, size);
    }

    private static SubsidyReportRow toReportRow(SubsidyGroupRow row) {
        return new SubsidyReportRow(
                IDS.toApi(row.merchantId),
                row.period,
                row.orderCount,
                scale2(row.totalPaidAmount),
                scale2(row.totalMerchantShare),
                scale2(row.totalSplitAmount),
                scale2(row.totalReversedAmount),
                scale2(row.totalSubsidyDue));
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2);
    }

    private static void requireAdminOrSystemQuery(QueryContext ctx) {
        if (ctx == null || (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR
                && ctx.operatorType() != OperatorType.SYSTEM)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "subsidy report requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return IDS.fromApi(raw);
        } catch (IllegalArgumentException bad) {
            throw invalid("invalid merchantId");
        }
    }

    private static ApiException invalid(String msg) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg);
    }
}
