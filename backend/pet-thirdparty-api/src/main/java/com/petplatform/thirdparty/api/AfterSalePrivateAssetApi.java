package com.petplatform.thirdparty.api;

import com.petplatform.common.CommandContext;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetContent;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;

/** Contract50 typed evidence boundary; never aliases merchant application/revision identifiers. */
public interface AfterSalePrivateAssetApi {
    String PURPOSE = "AFTERSALE_EVIDENCE";
    List<Asset> requireReadyOwned(String ownerUserId,List<String> assetIds,DataSource transactionSource);
    void requireStillReady(Asset asset,DataSource transactionSource);
    Grant issue(Issue command);
    PrivateAssetContent consume(Consume command);
    record Asset(String assetId,String ownerUserId,String objectSha256,String objectVersionRef,
                 String factVersion,String mediaType,long bytes) {}
    record Issue(CommandContext context,String afterSaleId,String evidenceBatchId,String assetId,String reason) {}
    record Consume(CommandContext context,String token) {}
    record Grant(String token,OffsetDateTime expiresAt) {}
}
