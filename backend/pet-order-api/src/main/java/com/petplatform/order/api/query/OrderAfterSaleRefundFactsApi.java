package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import java.math.BigDecimal;
public interface OrderAfterSaleRefundFactsApi {
 Fact requireDecidedRefund(String orderId,String paymentId,String storeId,QueryContext context);
 record Fact(OrderRefundOriginFact origin,String refundType,BigDecimal refundAmount,String sourceStage,String fundingEvidenceId) {}
}
