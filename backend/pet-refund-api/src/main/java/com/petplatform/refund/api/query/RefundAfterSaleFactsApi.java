package com.petplatform.refund.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import javax.sql.DataSource;
public interface RefundAfterSaleFactsApi {
 CreatedFact requireCreated(String refundId,String caseId,String decisionId,String storeId,QueryContext context,DataSource transactionSource);
 record CreatedFact(RefundExecutionFact execution,String fundingEvidenceId) {}
}
