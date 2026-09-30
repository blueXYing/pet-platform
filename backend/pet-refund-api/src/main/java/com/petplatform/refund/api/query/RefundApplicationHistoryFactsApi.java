package com.petplatform.refund.api.query;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
public interface RefundApplicationHistoryFactsApi {
 Fact readForOrder(String orderId,String storeId,QueryContext context,DataSource transactionSource);
 record Rejected(String applicationId,String decisionId,OffsetDateTime decidedAt,String actorId,String applicationVersion) {}
 record Active(String applicationId,String status,String version,String decisionId) {}
 record Fact(String orderId,String storeId,String userId,String merchantId,OffsetDateTime checkedAt,Rejected latestRejected,Active activeApplication,boolean refundExists) {}
}
