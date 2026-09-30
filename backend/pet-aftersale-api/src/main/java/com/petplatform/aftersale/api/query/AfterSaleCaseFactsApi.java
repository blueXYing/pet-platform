package com.petplatform.aftersale.api.query;

import com.petplatform.common.QueryContext;
import javax.sql.DataSource;

/** Leaf facts: never calls ORDER or eligibility. Caller holds the same store guard and writable RC transaction. */
public interface AfterSaleCaseFactsApi {
    CaseFact requireCurrent(String orderId, String storeId, String expectedCurrentCaseId,
            QueryContext context, DataSource transactionSource);
    record CaseFact(String afterSaleId, String orderId, String userId, String merchantId,
            String storeId, String status, String sourceStage, boolean active, String version) {}
}
