package com.petplatform.aftersale.api.query;

import com.petplatform.common.CommandContext;
import javax.sql.DataSource;

/** Bound immutable evidence plus current business authorization, for the typed private-asset adapter. */
public interface AfterSaleEvidenceAccessApi {
    EvidenceAccess proveAccess(CommandContext context, String afterSaleId, String batchId,
            String assetId, DataSource transactionSource);
    record EvidenceAccess(String afterSaleId, String batchId, String assetId, String ownerUserId,
            String objectSha256, String objectVersionRef, String assetFactVersion,
            String merchantId, String storeId, String caseVersion, String authzVersion) {}
}
