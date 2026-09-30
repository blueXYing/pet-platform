package com.petplatform.thirdparty.biz.apiimpl;

import com.petplatform.common.*;
import com.petplatform.thirdparty.api.*;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetContent;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.thirdparty.biz.infrastructure.persistence.AfterSaleAssetStore;
import com.petplatform.thirdparty.biz.infrastructure.persistence.entity.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Typed AFS grants. Business authorization precedes asset locks; object IO holds neither. */
public final class AfterSalePrivateAssetApiImpl implements AfterSalePrivateAssetApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;
    private final AfterSaleAssetStore store;
    private final SnowflakeIdGenerator ids;
    private final AfterSaleAssetReadAuthorizer authorization;
    private final PrivateObjectStore objects;
    private final PrivateAssetGrantKeyProvider keys;
    private final PrivateAssetReasonProtector reasons;
    private final PrivateAssetWatermarkRenderer watermarks;

    public AfterSalePrivateAssetApiImpl(DataSource source,SnowflakeIdGenerator ids,AfterSaleAssetReadAuthorizer authorization,
            PrivateObjectStore objects,PrivateAssetGrantKeyProvider keys,PrivateAssetReasonProtector reasons,
            PrivateAssetWatermarkRenderer watermarks) {
        this.source=Objects.requireNonNull(source);this.store=new AfterSaleAssetStore(source);
        this.ids=Objects.requireNonNull(ids);this.authorization=Objects.requireNonNull(authorization);
        this.objects=Objects.requireNonNull(objects);this.keys=Objects.requireNonNull(keys);
        this.reasons=Objects.requireNonNull(reasons);this.watermarks=Objects.requireNonNull(watermarks);
    }

    @Override public List<Asset> requireReadyOwned(String owner,List<String> requested,DataSource transactionSource) {
        requireTransaction(transactionSource);long ownerId=id(owner);
        if(requested==null||requested.size()>6)throw invalid();
        var ordered=new TreeSet<Long>();for(String value:requested)if(!ordered.add(id(value)))throw invalid();
        return store.read(tx->{var byId=new HashMap<String,Asset>();
            for(long assetId:ordered){var row=tx.assets().selectAssetForUpdate(assetId);ready(row);
                if(row.getOwnerUserId()!=ownerId)throw forbidden();byId.put(Long.toString(assetId),fact(row));}
            return requested.stream().map(byId::get).toList();});
    }
    @Override public void requireStillReady(Asset expected,DataSource transactionSource) {
        requireTransaction(transactionSource);if(expected==null)throw invalid();
        store.read(tx->{var row=tx.assets().selectAssetForUpdate(id(expected.assetId()));ready(row);
            if(!expected.equals(fact(row)))throw forbidden();return null;});
    }

    @Override public Grant issue(Issue c) {
        if(c==null)throw invalid();context(c.context());principal(c.context(),c.principal());id(c.afterSaleId());id(c.evidenceBatchId());id(c.assetId());
        if(c.reason()==null||c.reason().isBlank()||c.reason().length()>500
                ||c.reason().codePoints().anyMatch(value->value>=0xD800&&value<=0xDFFF))throw invalid();noCallerTransaction();
        return store.transaction(tx->{
            var proof=prove(c.context(),c.principal(),c.afterSaleId(),c.evidenceBatchId(),c.assetId());
            var asset=tx.assets().selectAssetForUpdate(id(c.assetId()));match(proof,asset);
            byte[] requestHash=hash(c.principal()==null?frame(c.afterSaleId(),c.evidenceBatchId(),c.assetId(),c.reason()):
                    frame("AFTERSALE_EVIDENCE_ISSUE_V2",c.principal().party(),c.afterSaleId(),c.evidenceBatchId(),c.assetId(),c.reason()));
            byte[] proofHash=proofHash(proof);
            var key=key();
            var existing=tx.grants().byRequest(c.context().operatorType().name(),id(c.context().operatorId()),id(c.afterSaleId()),c.context().requestId());
            if(existing!=null){
                if(!equal(existing.requestHash,requestHash))throw conflict();
                if(Boolean.TRUE.equals(existing.expired)||!"ISSUED".equals(existing.status)
                        ||!equal(existing.proofHash,proofHash)||!key.keyVersion().equals(existing.keyVersion))throw gone();
                String token=token(key,existing);if(!equal(hash(token.getBytes(StandardCharsets.US_ASCII)),existing.tokenDigest))throw unavailable();
                audit(tx,existing,"ISSUE","REPLAY",c.context());return new Grant(token,existing.expiresAt.atOffset(ZoneOffset.UTC));
            }
            var row=new AfterSaleAssetGrantEntity();row.id=next();row.afterSaleId=id(c.afterSaleId());row.batchId=id(c.evidenceBatchId());
            row.assetId=id(c.assetId());row.actorType=c.context().operatorType().name();row.actorId=id(c.context().operatorId());
            row.requestId=c.context().requestId();row.requestHash=requestHash;row.proofHash=proofHash;row.keyVersion=key.keyVersion();
            row.reasonCipher=reasons.protect("aftersale-evidence-read-reason",c.reason());
            if(row.reasonCipher==null||row.reasonCipher.length<16||row.reasonCipher.length>4096)throw unavailable();
            String token=token(key,row);row.tokenDigest=hash(token.getBytes(StandardCharsets.US_ASCII));
            if(tx.grants().insert(row)!=1)throw unavailable();var persisted=tx.grants().lock(row.id);
            if(persisted==null)throw unavailable();audit(tx,persisted,"ISSUE","SUCCESS",c.context());
            return new Grant(token,persisted.expiresAt.atOffset(ZoneOffset.UTC));
        });
    }

    @Override public PrivateAssetContent consume(Consume c) {
        if(c==null)throw invalid();context(c.context());principal(c.context(),c.principal());noCallerTransaction();
        if(c.token()==null||!c.token().matches("[A-Za-z0-9_-]{43}"))throw gone();
        byte[] digest=hash(c.token().getBytes(StandardCharsets.US_ASCII));
        var hint=store.read(tx->tx.grants().byDigest(digest));if(hint==null)throw gone();
        try {
            var admission=store.transaction(tx->{
                var proof=prove(c.context(),c.principal(),hint.afterSaleId.toString(),hint.batchId.toString(),hint.assetId.toString());
                var asset=tx.assets().selectAssetForUpdate(hint.assetId);match(proof,asset);
                var row=tx.grants().lock(hint.id);validateGrant(row,c.context(),digest,proofHash(proof),"ISSUED");
                if(tx.grants().consume(row.id)!=1)throw gone();audit(tx,row,"CONSUME","STARTED",c.context());
                return new ReadAdmission(row,asset.getObjectKey(),asset.getObjectVersionRef(),asset.getObjectSha256(),asset.getMediaType(),asset.getBytes());
            });
            // Never hold ORDER/AFS/ADMIN/asset locks across storage or image processing.
            var raw=objects.get(admission.objectKey(),admission.version());
            if(raw==null||raw.content()==null||raw.content().length!=admission.bytes()
                    ||!admission.sha().equals(raw.sha256())||!admission.sha().equals(hex(hash(raw.content())))
                    ||!admission.media().equals(raw.mediaType()))throw unavailable();
            var rendered=watermarks.renderResource(raw.content(),raw.mediaType(),
                    new PrivateAssetWatermarkRenderer.ResourceWatermark(c.context().operatorId(),"AFTERSALE",hint.afterSaleId.toString(),Instant.now().atOffset(ZoneOffset.UTC)));
            if(rendered==null||rendered.content()==null||rendered.content().length==0||rendered.content().length>20*1024*1024
                    ||!Set.of("image/jpeg","image/png").contains(rendered.mediaType()))throw unavailable();
            return store.transaction(tx->{
                var proof=prove(c.context(),c.principal(),hint.afterSaleId.toString(),hint.batchId.toString(),hint.assetId.toString());
                var asset=tx.assets().selectAssetForUpdate(hint.assetId);match(proof,asset);
                var row=tx.grants().lock(hint.id);validateGrant(row,c.context(),digest,proofHash(proof),"CONSUMED");
                if(!asset.getObjectSha256().equals(admission.sha())||!asset.getObjectVersionRef().equals(admission.version()))throw forbidden();
                audit(tx,row,"CONSUME","SUCCESS",c.context());
                return new PrivateAssetContent(rendered.content(),rendered.mediaType(),admission.sha(),rendered.content().length);
            });
        } catch(RuntimeException failure){
            store.transaction(tx->{audit(tx,hint,"CONSUME",failure instanceof ApiException e&&CommonApiCodes.FORBIDDEN.equals(e.code())?"DENIED":"FAILED",c.context());return null;});
            if(failure instanceof ApiException known)throw known;throw unavailable();
        }
    }

    private record ReadAdmission(AfterSaleAssetGrantEntity grant,String objectKey,String version,String sha,String media,long bytes) {}
    private AfterSaleAssetReadAuthorizer.Proof prove(CommandContext c,EvidencePrincipal principal,String caseId,String batch,String asset) {
        var p=principal==null?authorization.authorize(c,caseId,batch,asset,source):authorization.authorize(principal,caseId,batch,asset,source);
        if(p==null||!caseId.equals(p.afterSaleId())||!batch.equals(p.batchId())||!asset.equals(p.assetId())
                ||!c.operatorType().name().equals(p.actorType())||!c.operatorId().equals(p.actorId())
                ||p.sessionId()==null||p.sessionId().isBlank()||p.sessionGeneration()<0
                ||p.authzVersion()==null||p.authzVersion().isBlank()||p.scopeVersion()==null||p.scopeVersion().isBlank()
                ||!(c.operatorType()==OperatorType.USER?"MINIAPP":"ADMIN_WEB").equals(p.audience()))throw forbidden();
        if(principal!=null&&(!principal.party().equals(p.party())||!principal.audience().equals(p.audience())
                ||!principal.sessionId().equals(p.sessionId())||principal.sessionGeneration()!=p.sessionGeneration()))throw forbidden();
        if(principal==null&&p.party()!=null)throw forbidden();
        return p;
    }
    private static void match(AfterSaleAssetReadAuthorizer.Proof p,PrivateAssetEntity a) {
        ready(a);
        if(!Long.toString(a.getOwnerUserId()).equals(p.ownerUserId())||!a.getObjectSha256().equals(p.objectSha256())
                ||!a.getObjectVersionRef().equals(p.objectVersionRef())||!Long.toString(a.getVersion()).equals(p.assetFactVersion()))throw forbidden();
    }
    private static void ready(PrivateAssetEntity a){
        if(a==null||!PURPOSE.equals(a.getPurpose()))throw forbidden();
        if(!"READY".equals(a.getStatus()))throw new ApiException(PrivateAssetApiCodes.ASSET_NOT_READY,"Evidence asset is not available");
        if(a.getOwnerUserId()<=0||a.getVersion()<0||a.getObjectSha256()==null||!a.getObjectSha256().matches("[0-9a-f]{64}")
                ||a.getObjectVersionRef()==null||a.getObjectVersionRef().isBlank()||a.getBytes()==null||a.getBytes()<=0
                ||a.getMediaType()==null||!Set.of("image/jpeg","image/png").contains(a.getMediaType()))throw unavailable();
    }
    private static Asset fact(PrivateAssetEntity a){return new Asset(Long.toString(a.getId()),Long.toString(a.getOwnerUserId()),a.getObjectSha256(),
            a.getObjectVersionRef(),Long.toString(a.getVersion()),a.getMediaType(),a.getBytes());}
    private static byte[] proofHash(AfterSaleAssetReadAuthorizer.Proof p){
        byte[] legacy=frame(p.audience(),p.sessionId(),p.sessionGeneration(),p.actorType(),p.actorId(),
                p.afterSaleId(),p.batchId(),p.assetId(),p.ownerUserId(),p.objectSha256(),p.objectVersionRef(),p.assetFactVersion(),p.authzVersion(),p.scopeVersion());
        return hash(p.party()==null?legacy:frame("AFTERSALE_EVIDENCE_PROOF_V2",p.party(),hex(hash(legacy))));
    }
    private static void validateGrant(AfterSaleAssetGrantEntity row,CommandContext c,byte[] digest,byte[] proof,String state){
        if(row==null||Boolean.TRUE.equals(row.expired)||!state.equals(row.status))throw gone();
        if(!row.actorType.equals(c.operatorType().name())||row.actorId!=id(c.operatorId())||!equal(row.tokenDigest,digest)||!equal(row.proofHash,proof))throw forbidden();
    }
    private void audit(AfterSaleAssetStore.Tx tx,AfterSaleAssetGrantEntity row,String action,String result,CommandContext c){
        if(tx.grants().audit(next(),row.id,action,result,c.operatorType().name(),id(c.operatorId()),c.requestId())!=1)throw unavailable();
    }
    private PrivateAssetGrantKeyProvider.KeyMaterial key(){
        var k=keys.current();if(k==null||k.keyVersion()==null||!k.keyVersion().matches("[A-Za-z0-9._-]{1,64}")
                ||k.hmacKey()==null||k.hmacKey().getEncoded()==null||k.hmacKey().getEncoded().length<32)throw unavailable();return k;
    }
    private static String token(PrivateAssetGrantKeyProvider.KeyMaterial key,AfterSaleAssetGrantEntity row){
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(key.hmacKey());return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(frame("AFTERSALE_EVIDENCE_READ_V1",row.id,row.actorType,row.actorId,row.afterSaleId,row.requestId,hex(row.proofHash))));}
        catch(Exception invalid){throw unavailable();}
    }
    private void requireTransaction(DataSource other){
        if(other!=source||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(source)
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED))throw unavailable();
    }
    private static void noCallerTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();}
    private static void context(CommandContext c){
        try {PublicContractChecks.requireCommandRequestId(c);id(c.operatorId());
            if(c.operatorType()!=OperatorType.USER&&c.operatorType()!=OperatorType.PLATFORM_OPERATOR)throw forbidden();
        } catch(IllegalArgumentException invalid){throw invalid();}
    }
    private static void principal(CommandContext c,EvidencePrincipal p){
        if(p==null)return;
        if(!c.equals(p.context())||p.party()==null||!Set.of("USER","MERCHANT","OPS").contains(p.party())
                ||p.sessionId()==null||p.sessionId().isBlank()||p.sessionGeneration()<0)throw invalid();
        boolean ops="OPS".equals(p.party());
        if((ops?OperatorType.PLATFORM_OPERATOR:OperatorType.USER)!=c.operatorType()
                ||!(ops?"ADMIN_WEB":"MINIAPP").equals(p.audience()))throw forbidden();
    }
    private long next(){long value=ids.nextId();if(value<=0)throw unavailable();return value;}
    private static long id(String value){try{return IDS.fromApi(value);}catch(RuntimeException invalid){throw invalid();}}
    private static boolean equal(byte[] a,byte[] b){return a!=null&&b!=null&&MessageDigest.isEqual(a,b);}
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception e){throw unavailable();}}
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    private static byte[] frame(Object... values){
        try {var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);
            for(Object value:values){if(value==null){data.writeInt(-1);continue;}byte[] b=value.toString().getBytes(StandardCharsets.UTF_8);data.writeInt(b.length);data.write(b);}return out.toByteArray();
        }catch(IOException e){throw unavailable();}
    }
    private static ApiException invalid(){return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid evidence access request");}
    private static ApiException forbidden(){return new ApiException(CommonApiCodes.FORBIDDEN,"Evidence access denied");}
    private static ApiException conflict(){return new ApiException(CommonApiCodes.CONFLICT,"Evidence request parameters conflict");}
    private static ApiException gone(){return new ApiException(PrivateAssetApiCodes.GRANT_GONE,"Evidence read grant is unavailable");}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Evidence dependency unavailable");}
}
