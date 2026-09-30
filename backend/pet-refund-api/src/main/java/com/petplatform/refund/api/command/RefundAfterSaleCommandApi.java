package com.petplatform.refund.api.command;
import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
public interface RefundAfterSaleCommandApi {
 Created create(Create command,DataSource transactionSource);
 record Create(CommandContext context,String orderId,String storeId,String caseId,String decisionId,String orderToken) {}
 record Created(String refundOrderId,String refundNo,String refundType,BigDecimal refundAmount,BigDecimal originalPaidAmount,String createdEventId,OffsetDateTime createdAt) {}
}
