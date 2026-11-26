package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** MyBatis XML mapper for read-only ORDER settlement facts. */
public interface OrderSettlementFactsMapper {
    SettlementOrderRow selectForSettlement(long orderId);

    final class SettlementOrderRow {
        public long id;
        public long merchantId;
        public long storeId;
        public String orderStage;
        public String paymentStatus;
        public String verificationStatus;
        public BigDecimal originalPrice;
        public BigDecimal flashDiscount;
        public BigDecimal discountAmount;
        public BigDecimal payAmount;
        public BigDecimal refundedAmount;
        public Long currentRefundApplicationId;
        public Long refundOrderId;
        public Long currentAftersaleId;
        public LocalDateTime verifiedAt;
    }
}
