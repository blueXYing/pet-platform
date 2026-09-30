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
    /** Trusted boundary snapshot, never deserialized from a client body. Each use rechecks the real session. */
    record EvidencePrincipal(CommandContext context,String audience,String sessionId,long sessionGeneration,String party) {}
    record Issue(CommandContext context,String afterSaleId,String evidenceBatchId,String assetId,String reason,
                 EvidencePrincipal principal) {
        /** Compatibility for internal callers predating route-specific HTTP grants. */
        public Issue(CommandContext context,String afterSaleId,String evidenceBatchId,String assetId,String reason){
            this(context,afterSaleId,evidenceBatchId,assetId,reason,null);
        }
        public Issue(EvidencePrincipal principal,String afterSaleId,String evidenceBatchId,String assetId,String reason){
            this(java.util.Objects.requireNonNull(principal).context(),afterSaleId,evidenceBatchId,assetId,reason,principal);
        }
    }
    record Consume(CommandContext context,String token,EvidencePrincipal principal) {
        public Consume(CommandContext context,String token){this(context,token,null);}
        public Consume(EvidencePrincipal principal,String token){this(java.util.Objects.requireNonNull(principal).context(),token,principal);}
    }
    record Grant(String token,OffsetDateTime expiresAt) {}
}
