package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.user.biz.application.UserAuthService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Real HTTP sessions -> typed grants -> real AFS/asset proofs. Only scanner/OSS are external doubles. */
class AfterSaleEvidenceHttpAcceptanceTest {
    @Test void threePartiesReadBothEvidenceOwnersThroughSingleUseRouteBoundGrants()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            var unsupported=f.send("POST","/c/aftersale-evidence-assets",Map.of("file","not multipart"),f.buyerToken);
            assertEquals(415,unsupported.status());assertEquals("COMMON_INVALID_ARGUMENT",unsupported.envelope().get("code"));
            assertEquals("no-store, private",unsupported.headers().firstValue("Cache-Control").orElseThrow());
            byte[] picture=picture(0x2277aa);String requestId=AfterSaleHttpFixture.rid();
            var uploaded=upload(f,f.buyerToken,requestId,picture);assertEquals(201,uploaded.status());
            String buyerAsset=uploaded.value("assetId");
            var replay=upload(f,f.buyerToken,requestId,picture);assertEquals(200,replay.status());assertEquals(buyerAsset,replay.value("assetId"));
            assertEquals(409,upload(f,f.buyerToken,requestId,picture(0x225577)).status());
            String order=f.verifiedOrder();var body=AfterSaleHttpFixture.createBody();body.put("evidenceAssetIds",List.of(buyerAsset));
            var created=f.send("POST","/c/orders/"+order+"/aftersales",body,f.buyerToken);assertEquals(201,created.status(),created.toString());
            String caseId=created.value("afterSaleId"),batch=created.value("evidenceBatchId");
            String issuePath=issuePath("c",caseId,batch,buyerAsset);
            var issueHeaders=Map.of("Authorization","Bearer "+f.buyerToken,"X-Request-Id",AfterSaleHttpFixture.rid());
            var issued=f.send("POST",issuePath,Map.of("reason","View the evidence I submitted"),issueHeaders);assertEquals(200,issued.status(),issued.toString());
            var repeated=f.send("POST",issuePath,Map.of("reason","View the evidence I submitted"),issueHeaders);
            assertEquals(200,repeated.status());assertEquals(issued.value("readUrl"),repeated.value("readUrl"));
            var unicodeHeaders=Map.of("Authorization","Bearer "+f.buyerToken,"X-Request-Id",AfterSaleHttpFixture.rid());
            assertEquals(200,f.send("POST",issuePath,Map.of("reason","Read evidence ?"),unicodeHeaders).status());
            long grantsBefore=f.count("SELECT COUNT(*) FROM aftersale_asset_read_grant");
            long auditsBefore=f.count("SELECT COUNT(*) FROM aftersale_asset_access_audit");
            assertEquals(400,f.sendRaw("POST",issuePath,"{\"reason\":\"Read evidence \\uD800\"}",unicodeHeaders).status());
            assertEquals(grantsBefore,f.count("SELECT COUNT(*) FROM aftersale_asset_read_grant"));
            assertEquals(auditsBefore,f.count("SELECT COUNT(*) FROM aftersale_asset_access_audit"));
            assertTrue(issued.value("expiresAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
            assertEquals(409,f.send("POST",issuePath,Map.of("reason","Different purpose with the same request"),issueHeaders).status());
            assertEquals(403,f.send("POST",issuePath,Map.of("reason","Owner cannot borrow the buyer route"),f.ownerToken).status());
            assertEquals(403,f.send("POST",issuePath,Map.of("reason","Unrelated user cannot inspect evidence"),f.otherToken).status());
            assertEquals(403,read(f,issued.value("readUrl").replace("/c/","/merchant/"),f.buyerToken).statusCode());
            assertEquals(403,read(f,issued.value("readUrl"),f.ownerToken).statusCode());
            var first=read(f,issued.value("readUrl"),f.buyerToken);image(first,picture);
            assertEquals(410,read(f,issued.value("readUrl"),f.buyerToken).statusCode());
            assertEquals(410,f.send("POST",issuePath,Map.of("reason","View the evidence I submitted"),issueHeaders).status());
            for(var party:List.of(new Party("merchant",f.ownerToken),new Party("admin",f.adminToken))){
                var grant=issue(f,party,caseId,batch,buyerAsset);image(read(f,grant.value("readUrl"),party.token()),picture);
            }

            var ownerUpload=upload(f,f.ownerToken,AfterSaleHttpFixture.rid(),picture(0x994455));assertEquals(201,ownerUpload.status());
            String ownerAsset=ownerUpload.value("assetId");
            var submitted=f.send("POST","/merchant/aftersales/"+caseId+"/evidence",
                    Map.of("expectedVersion",created.value("version"),"text","Merchant service evidence from the current owner","evidenceAssetIds",List.of(ownerAsset)),f.ownerToken);
            assertEquals(200,submitted.status(),submitted.toString());
            String ownerBatch=submitted.value("evidenceBatchId");
            for(var party:List.of(new Party("c",f.buyerToken),new Party("merchant",f.ownerToken),new Party("admin",f.adminToken))){
                var grant=issue(f,party,caseId,ownerBatch,ownerAsset);assertEquals(200,read(f,grant.value("readUrl"),party.token()).statusCode());
            }
            // Both assets are real and owned by participants; the wrong immutable batch still cannot authorize one.
            assertEquals(403,f.send("POST",issuePath("c",caseId,batch,ownerAsset),Map.of("reason","Wrong evidence batch binding"),f.buyerToken).status());

            // Authorization-only fixture change: the buyer now also owns this merchant. Business proofs stay untouched.
            assertEquals(1,f.jdbc.update("UPDATE merchant SET owner_user_id=?,version=version+1 WHERE id=?",Long.parseLong(AfterSaleHttpFixture.BUYER),Long.parseLong(AfterSaleHttpFixture.MERCHANT)));
            var bothRolesHeaders=Map.of("Authorization","Bearer "+f.buyerToken,"X-Request-Id",AfterSaleHttpFixture.rid());
            var bothRolesBody=Map.of("reason","Same actor reading in two explicitly authorized roles");
            var buyerRole=f.send("POST",issuePath("c",caseId,ownerBatch,ownerAsset),bothRolesBody,bothRolesHeaders);
            assertEquals(200,buyerRole.status(),buyerRole.toString());
            var changedRole=f.send("POST",issuePath("merchant",caseId,ownerBatch,ownerAsset),bothRolesBody,bothRolesHeaders);
            assertEquals(409,changedRole.status(),changedRole.toString());
            // A new request demonstrates that MERCHANT is valid too; 409 above came from key binding, not missing OWNER authority.
            var merchantRole=f.send("POST",issuePath("merchant",caseId,ownerBatch,ownerAsset),bothRolesBody,f.buyerToken);
            assertEquals(200,merchantRole.status(),merchantRole.toString());
            assertEquals(403,read(f,buyerRole.value("readUrl").replace("/c/","/merchant/"),f.buyerToken).statusCode());
            assertEquals(200,read(f,buyerRole.value("readUrl"),f.buyerToken).statusCode());
            assertEquals(200,read(f,merchantRole.value("readUrl"),f.buyerToken).statusCode());
            assertEquals(2,f.count("SELECT COUNT(*) FROM private_asset WHERE purpose='AFTERSALE_EVIDENCE'"));
            assertEquals(0,f.channelCalls.get());
        }
    }

    @Test void revocationAndQuarantineDuringObjectReadReturnNoImageAndKeepGrantConsumed()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String asset=upload(f,f.buyerToken,AfterSaleHttpFixture.rid(),picture(0x55aa66)).value("assetId");
            var body=AfterSaleHttpFixture.createBody();body.put("evidenceAssetIds",List.of(asset));
            var created=f.send("POST","/c/orders/"+f.verifiedOrder()+"/aftersales",body,f.buyerToken);assertEquals(201,created.status(),created.toString());
            String caseId=created.value("afterSaleId"),batch=created.value("evidenceBatchId");
            var grant=issue(f,new Party("c",f.buyerToken),caseId,batch,asset);
            String revoked=f.buyerToken;
            f.afterObjectRead.set(()->f.context.getBean(UserAuthService.class).logout(AfterSaleHttpFixture.rid(),revoked));
            var denied=read(f,grant.value("readUrl"),revoked);noImage(denied,401);
            assertEquals(1,f.count("SELECT COUNT(*) FROM aftersale_asset_read_grant WHERE status='CONSUMED'"));

            f.loginBuyer();var second=issue(f,new Party("c",f.buyerToken),caseId,batch,asset);
            f.afterObjectRead.set(()->f.jdbc.update("UPDATE private_asset SET status='QUARANTINED',version=version+1 WHERE id=?",Long.parseLong(asset)));
            var quarantined=read(f,second.value("readUrl"),f.buyerToken);noImage(quarantined,409);
            assertEquals(2,f.count("SELECT COUNT(*) FROM aftersale_asset_read_grant WHERE status='CONSUMED'"));
            assertEquals(0,f.channelCalls.get());
        }
    }

    private record Party(String path,String token){}
    private static String issuePath(String party,String caseId,String batch,String asset){return "/"+party+"/aftersales/"+caseId+"/evidence-batches/"+batch+"/assets/"+asset+"/read-grants";}
    private static AfterSaleHttpFixture.Reply issue(AfterSaleHttpFixture f,Party party,String caseId,String batch,String asset)throws Exception{
        var result=f.send("POST",issuePath(party.path(),caseId,batch,asset),Map.of("reason","Review case evidence under current authorization"),party.token());
        assertEquals(200,result.status(),result.toString());return result;
    }
    private static AfterSaleHttpFixture.Reply upload(AfterSaleHttpFixture f,String token,String requestId,byte[] image)throws Exception{
        String boundary="AFSEvidence_"+UUID.randomUUID().toString().replace("-","");
        var body=new ByteArrayOutputStream();body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"e.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(image);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(URI.create(f.baseUrl+"/c/aftersale-evidence-assets")).timeout(Duration.ofSeconds(20))
                .header("Authorization","Bearer "+token).header("X-Request-Id",requestId).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response=f.client.send(request,HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked") Map<String,Object> envelope=f.json.readValue(response.body(),Map.class);
        assertFalse(envelope.containsKey("success"));assertEquals(Set.of("code","message","data","traceId"),envelope.keySet());
        assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(),envelope.get("traceId"));
        assertEquals("no-store, private",response.headers().firstValue("Cache-Control").orElseThrow());
        return new AfterSaleHttpFixture.Reply(response.statusCode(),envelope,response.headers());
    }
    private static HttpResponse<byte[]> read(AfterSaleHttpFixture f,String url,String token)throws Exception{
        var response=f.client.send(HttpRequest.newBuilder(URI.create(f.baseUrl).resolve(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()>=400){
            var envelope=f.json.readTree(new String(response.body(),StandardCharsets.UTF_8));
            assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(),envelope.get("traceId").textValue());
        }
        return response;
    }
    private static void image(HttpResponse<byte[]> response,byte[] original){
        assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("image/"));
        assertEquals("no-store, private",response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        assertFalse(Arrays.equals(original,response.body()),"served content must carry the real watermark");
    }
    private static void noImage(HttpResponse<byte[]> response,int status){
        assertEquals(status,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertEquals("no-store, private",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(new String(response.body(),StandardCharsets.UTF_8).contains("\"data\":null"));
    }
    private static byte[] picture(int rgb)throws Exception{
        var image=new BufferedImage(160,100,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,rgb);
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",bytes));return bytes.toByteArray();
    }
}
