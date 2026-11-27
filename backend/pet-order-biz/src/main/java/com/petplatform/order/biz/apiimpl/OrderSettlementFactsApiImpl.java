package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderSettlementFactsStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderSettlementFactsMapper.SettlementOrderRow;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import javax.sql.DataSource;

/** Read-only ORDER facts implementation for PAYMENT settlement kernel (Contract 78 §3). */
public final class OrderSettlementFactsApiImpl implements OrderSettlementFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final OrderSettlementFactsStore store;

    public OrderSettlementFactsApiImpl(DataSource source) {
        this.store = new OrderSettlementFactsStore(source);
    }

    @Override
    public OrderSettlementFact readForSettlement(String orderId, QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.SYSTEM) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "ORDER settlement facts require SYSTEM");
        }
        long id;
        try { id = IDS.fromApi(orderId); }
        catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid order id");
        }
        SettlementOrderRow row;
        try { row = store.findByOrderId(id); }
        catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "ORDER settlement facts unavailable");
        }
        if (row == null) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "order not found");
        }
        if (row.merchantId <= 0 || row.storeId <= 0 || row.originalPrice == null || row.payAmount == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "ORDER settlement facts incomplete");
        }
        return new OrderSettlementFact(
                IDS.toApi(row.id),
                IDS.toApi(row.merchantId),
                IDS.toApi(row.storeId),
                "CANCELED".equals(row.orderStage),
                "VERIFIED".equals(row.verificationStatus),
                row.originalPrice,
                row.flashDiscount == null ? BigDecimal.ZERO.setScale(2) : row.flashDiscount,
                row.discountAmount == null ? BigDecimal.ZERO.setScale(2) : row.discountAmount,
                row.payAmount,
                row.refundedAmount == null ? BigDecimal.ZERO.setScale(2) : row.refundedAmount,
                row.currentRefundApplicationId == null ? null : IDS.toApi(row.currentRefundApplicationId),
                row.refundOrderId == null ? null : IDS.toApi(row.refundOrderId),
                row.currentAftersaleId == null ? null : IDS.toApi(row.currentAftersaleId),
                row.verifiedAt == null ? null : row.verifiedAt.atOffset(ZoneOffset.UTC),
                // Contract 79 section 6.2: NULL reads as 0.00 so legacy rows keep their meaning.
                row.merchantCouponDiscount == null ? BigDecimal.ZERO.setScale(2) : row.merchantCouponDiscount);
    }
}
