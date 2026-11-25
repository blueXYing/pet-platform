package com.petplatform.payment.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.SettlementTypes.ListSettlementOrdersQuery;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementInstructionView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementPage;
import java.util.List;

/** Read-only query API for settlement orders and channel split instructions (Contract 78). */
public interface SettlementQueryApi {
    SettlementOrderView getById(String settlementOrderId, QueryContext context);
    SettlementOrderView getByPaymentId(String paymentId, QueryContext context);
    SettlementPage<SettlementOrderView> listOrders(ListSettlementOrdersQuery query);
    List<SettlementInstructionView> listInstructions(String settlementOrderId, QueryContext context);
    SettlementInstructionView getInstructionById(String instructionId, QueryContext context);
}
