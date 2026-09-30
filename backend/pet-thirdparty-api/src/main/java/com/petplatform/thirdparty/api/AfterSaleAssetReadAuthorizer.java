package com.petplatform.thirdparty.api;

import com.petplatform.common.CommandContext;
import javax.sql.DataSource;

/** Boot combines current real session and AFTERSALE public evidence-access facts. */
@FunctionalInterface
public interface AfterSaleAssetReadAuthorizer {
    Proof authorize(CommandContext context,String afterSaleId,String batchId,String assetId,DataSource source);
    record Proof(String audience,String sessionId,long sessionGeneration,String actorType,String actorId,
                 String afterSaleId,String batchId,String assetId,String ownerUserId,String objectSha256,
                 String objectVersionRef,String assetFactVersion,String authzVersion,String scopeVersion) {}
}
