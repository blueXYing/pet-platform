package com.petplatform.thirdparty.api;

import com.petplatform.common.CommandContext;
import javax.sql.DataSource;

/** Boot combines current real session and AFTERSALE public evidence-access facts. */
@FunctionalInterface
public interface AfterSaleAssetReadAuthorizer {
    Proof authorize(CommandContext context,String afterSaleId,String batchId,String assetId,DataSource source);
    /** Route-aware callers cannot fall back to generic participant authorization. */
    default Proof authorize(AfterSalePrivateAssetApi.EvidencePrincipal principal,String afterSaleId,String batchId,String assetId,DataSource source){
        throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "Explicit evidence audience authorization is unavailable");
    }
    record Proof(String audience,String sessionId,long sessionGeneration,String actorType,String actorId,
                 String afterSaleId,String batchId,String assetId,String ownerUserId,String objectSha256,
                 String objectVersionRef,String assetFactVersion,String authzVersion,String scopeVersion,String party) {
        public Proof(String audience,String sessionId,long sessionGeneration,String actorType,String actorId,
                String afterSaleId,String batchId,String assetId,String ownerUserId,String objectSha256,
                String objectVersionRef,String assetFactVersion,String authzVersion,String scopeVersion){
            this(audience,sessionId,sessionGeneration,actorType,actorId,afterSaleId,batchId,assetId,ownerUserId,
                    objectSha256,objectVersionRef,assetFactVersion,authzVersion,scopeVersion,null);
        }
    }
}
