package com.petplatform.payment.biz.settlement;

import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Checks live ORDER-side refund and after-sale blockers before executing an order split
 * (Ruling 40 §5.3 & CCR-PAY-CENTER-001 §4.2).
 */
@FunctionalInterface
public interface SettlementOrderBlockerPort {
    BlockerState inspect(long orderId);

    record BlockerState(
            BigDecimal refundedAmount,
            boolean refundInProgress,
            boolean afterSaleInProgress) {
        public BlockerState {
            if (refundedAmount == null || refundedAmount.signum() < 0 || refundedAmount.scale() > 2) {
                throw new IllegalArgumentException("invalid refunded amount");
            }
        }
    }

    /**
     * Default ORDER-API adapter: treats any open currentRefundApplicationId / refundOrderId (when
     * unpaid-refunded amount is still 0 or order has uncompleted refund) or currentAfterSaleId
     * conservatively as a blocker unless callers supply finer-grained refund/aftersale facts.
     */
    static SettlementOrderBlockerPort fromOrderFacts(OrderSettlementFactsApi orderFacts) {
        Objects.requireNonNull(orderFacts, "orderFacts");
        return orderId -> {
            OrderSettlementFact fact = orderFacts.readForSettlement(
                    Long.toString(orderId), new QueryContext(null, OperatorType.SYSTEM, null));
            boolean refundPending = fact.currentRefundApplicationId() != null
                    || (fact.refundOrderId() != null && fact.refundedAmount().signum() == 0);
            boolean afterSalePending = fact.currentAfterSaleId() != null;
            return new BlockerState(fact.refundedAmount(), refundPending, afterSalePending);
        };
    }

    static SettlementOrderBlockerPort none() {
        return orderId -> new BlockerState(BigDecimal.ZERO.setScale(2), false, false);
    }
}
