package com.petplatform.boot.config;

import com.petplatform.aftersale.api.query.AfterSaleEvidenceAccessApi;
import com.petplatform.aftersale.biz.application.AfterSalePorts;
import com.petplatform.thirdparty.api.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Public owner APIs only. The supplied authority is the same real session adapter as AFS commands. */
public final class AfterSaleAssetAdapters {
    private AfterSaleAssetAdapters() {}
    public static AfterSalePorts.Assets assets(AfterSalePrivateAssetApi api){
        Objects.requireNonNull(api);
        return new AfterSalePorts.Assets(){
            public List<AfterSalePorts.Asset> requireReadyOwned(String owner,List<String> ids,DataSource source){
                return api.requireReadyOwned(owner,ids,source).stream().map(a->new AfterSalePorts.Asset(a.assetId(),a.ownerUserId(),a.objectSha256(),
                        a.objectVersionRef(),a.factVersion(),a.mediaType(),a.bytes())).toList();
            }
            public void requireStillReady(AfterSalePorts.Asset a,DataSource source){api.requireStillReady(new AfterSalePrivateAssetApi.Asset(
                    a.assetId(),a.ownerUserId(),a.objectSha256(),a.objectVersionRef(),a.factVersion(),a.mediaType(),a.bytes()),source);}
        };
    }
    public static AfterSaleAssetReadAuthorizer authorizer(Supplier<AfterSaleEvidenceAccessApi> evidence,AfterSaleAuthorityAdapter authority){
        Objects.requireNonNull(evidence);Objects.requireNonNull(authority);
        return (context,caseId,batchId,assetId,source)->{
            var session=authority.currentSession(context);
            var proof=evidence.get().proveAccess(context,caseId,batchId,assetId,source);
            return new AfterSaleAssetReadAuthorizer.Proof(session.audience(),session.sessionId(),session.generation(),context.operatorType().name(),session.actorId(),
                    proof.afterSaleId(),proof.batchId(),proof.assetId(),proof.ownerUserId(),proof.objectSha256(),proof.objectVersionRef(),proof.assetFactVersion(),
                    proof.authzVersion(),AfterSaleAuthorityAdapter.revision(proof.merchantId()+":"+proof.storeId(),proof.caseVersion()));
        };
    }
}
