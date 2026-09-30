package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real loopback HTTP, current C/OWNER/ADMIN sessions and SQL proofs; no controller/service mocks. */
class AfterSaleHttpAcceptanceTest {
    @Test void realSessionsCompleteSupplementNonRefundFinalsWithdrawalAndDuplicateClosure()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder();
            assertTrue(f.context.getBeansOfType(RefundFundingEligibilityFactsApi.class).isEmpty());
            var eligibility=f.send("GET","/c/orders/"+order+"/aftersale-eligibility",null,f.buyerToken);
            assertEquals(200,eligibility.status());assertEquals(true,eligibility.data().get("eligible"));
            var body=createBody();var headers=bearer(f.buyerToken);
            var receipt=f.send("POST",createPath(order),body,headers);assertEquals(201,receipt.status());
            var replay=f.send("POST",createPath(order),body,headers);assertEquals(200,replay.status());assertEquals(receipt.data(),replay.data());
            String id=receipt.value("afterSaleId");assertPublicReceipt(receipt);
            for(String party:List.of("c","merchant","admin")){
                String token=token(f,party);
                var detail=f.send("GET","/"+party+"/aftersales/"+id,null,token);assertEquals(200,detail.status());
                assertEquals("VERIFIED",detail.value("sourceStage"));assertEquals(body.get("description"),detail.data().get("description"));
                String filter=party.equals("c")?"":"?merchantId="+MERCHANT+"&storeId="+STORE;
                var list=f.send("GET","/"+party+"/aftersales"+filter,null,token);assertEquals(200,list.status());
                assertEquals(1,((Number)list.data().get("total")).intValue());
                assertFalse(f.json.writeValueAsString(list.data()).contains(body.get("description").toString()));
            }
            receipt=accept(f,receipt);
            // Each supplement is requested and fulfilled by its true party through HTTP.
            for(String target:List.of("USER","MERCHANT")){
                String deadline=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(f.clock.instant().plusSeconds(600));
                receipt=f.send("POST",adminPath(id,"supplement-requests"),Map.of("expectedVersion",receipt.value("version"),"targetParty",target,"reason","Explain the observed service issue","deadline",deadline),f.adminToken);
                assertEquals(200,receipt.status());assertEquals("WAITING_SUPPLEMENT",receipt.value("status"));
                var evidence=Map.of("expectedVersion",receipt.value("version"),"supplementRequestId",receipt.value("supplementRequestId"),"text","Independent additional facts from "+target,"evidenceAssetIds",List.of());
                String party=target.equals("USER")?"c":"merchant";
                receipt=f.send("POST","/"+party+"/aftersales/"+id+"/evidence",evidence,token(f,party));
                assertEquals(200,receipt.status());assertEquals("PROCESSING",receipt.value("status"));
            }
            var opinion=Map.of("expectedVersion",receipt.value("version"),"opinionCode","PARTLY_AGREE","explanation","Merchant independently explains the partial disagreement","evidenceAssetIds",List.of());
            receipt=f.send("POST","/merchant/aftersales/"+id+"/opinion",opinion,f.ownerToken);assertEquals(200,receipt.status());
            receipt=decide(f,receipt,"REJECT");String firstFinal=id;
            assertEquals("RESOLVED",receipt.value("status"));assertNull(receipt.data().get("refundOrderId"));
            var missingStatement=f.send("POST",createPath(order),createBody(),f.buyerToken);assertEquals(400,missingStatement.status());
            body=createBody();body.put("newProblemStatement","A subsequently noticed problem distinct from the first report");
            receipt=f.send("POST",createPath(order),body,f.buyerToken);assertEquals(201,receipt.status());
            receipt=f.send("POST",adminPath(receipt.value("afterSaleId"),"close-duplicate"),Map.of("expectedVersion",receipt.value("version"),"priorFinalCaseId",firstFinal,"reason","Evidence shows the same previously decided problem"),f.adminToken);
            assertEquals(200,receipt.status());assertEquals("CLOSED",receipt.value("status"));assertNull(receipt.data().get("decisionId"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM aftersale_decision"));
            // All three withdrawal states retain the original eligibility clock and release the active pointer.
            for(String state:List.of("PENDING","PROCESSING","WAITING_SUPPLEMENT")){
                receipt=f.send("POST",createPath(order),body,f.buyerToken);assertEquals(201,receipt.status());
                if(!state.equals("PENDING"))receipt=accept(f,receipt);
                if(state.equals("WAITING_SUPPLEMENT"))receipt=f.send("POST",adminPath(receipt.value("afterSaleId"),"supplement-requests"),Map.of("expectedVersion",receipt.value("version"),"targetParty","USER","reason","Further explanation required","deadline","2030-01-01T09:10:20.000Z"),f.adminToken);
                assertEquals(state,receipt.value("status"));
                receipt=f.send("POST","/c/aftersales/"+receipt.value("afterSaleId")+"/withdraw",Map.of("expectedVersion",receipt.value("version")),f.buyerToken);
                assertEquals(200,receipt.status());assertEquals("WITHDRAWN",receipt.value("status"));
                var still=f.send("GET","/c/orders/"+order+"/aftersale-eligibility",null,f.buyerToken);
                assertEquals(eligibility.value("deadline"),still.value("deadline"));assertEquals(true,still.data().get("eligible"));
            }
            for(String type:List.of("RESERVICE","OTHER")){
                receipt=f.send("POST",createPath(order),body,f.buyerToken);assertEquals(201,receipt.status());
                receipt=accept(f,receipt);receipt=decide(f,receipt,type);assertEquals("RESOLVED",receipt.value("status"));
            }
            assertEquals(3,f.count("SELECT COUNT(*) FROM aftersale_decision"));assertNoMoney(f);
            assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM verification_record"));
        }
    }

    @Test void strictHttpDtoUuidBindingAndCasRejectBeforeSideEffectsAndMoneyIsClosed()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder(),path=createPath(order);var good=createBody();String raw=f.json.writeValueAsString(good);
            for(String bad:List.of(raw.substring(0,raw.length()-1)+",\"operatorId\":\"710101\"}",
                    raw.substring(0,raw.length()-1)+",\"orderId\":\""+order+"\"}",
                    raw.substring(0,raw.length()-1)+",\"typeCode\":\"QA_QUALITY\"}",raw+" {}",
                    raw.replace("\"QA_QUALITY\"","1"),raw.substring(0,raw.length()-1)+",\"requestedAmount\":12.50}")){
                assertEquals(400,f.sendRaw("POST",path,bad,bearer(f.buyerToken)).status(),bad);
            }
            // Raw JSON literals are intentional: serializing String test values would conceal
            // Jackson's Float -> String coercion (12.50 was observed creating a real case).
            for(String literal:List.of("12.50","1","true","false","[]","{}")){
                String bad=raw.substring(0,raw.length()-1)+",\"requestedAmount\":"+literal+"}";
                assertEquals(400,f.sendRaw("POST",path,bad,bearer(f.buyerToken)).status(),literal);
            }
            for(String literal:List.of("710100","710100.0","true","null")){
                String bad=raw.replace("\"evidenceAssetIds\":[]","\"evidenceAssetIds\":["+literal+"]");
                assertNotEquals(raw,bad,"the raw numeric/boolean ID probe must alter the actual array");
                assertEquals(400,f.sendRaw("POST",path,bad,bearer(f.buyerToken)).status(),literal);
            }
            for(String requestId:List.of("not-a-uuid",rid()+"x",rid().replace("-",""))){
                assertEquals(400,f.send("POST",path,good,Map.of("Authorization","Bearer "+f.buyerToken,"X-Request-Id",requestId)).status());
            }
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_case"));assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_command"));
            String upper=rid().toUpperCase(Locale.ROOT);var headers=Map.of("Authorization","Bearer "+f.buyerToken,"X-Request-Id",upper);
            var receipt=f.send("POST",path,good,headers);assertEquals(201,receipt.status());
            assertEquals(upper,f.text("SELECT CONVERT(request_id USING utf8mb4) FROM aftersale_command"));
            var changed=new LinkedHashMap<>(good);changed.put("description","A changed description with the same request identifier");
            assertEquals(409,f.send("POST",path,changed,headers).status());assertEquals(200,f.send("POST",path,good,headers).status());
            String id=receipt.value("afterSaleId");String accept=adminPath(id,"accept");
            long beforeInvalid=f.count("SELECT COUNT(*) FROM aftersale_command");
            for(String literal:List.of("0","0.0","true","false"))
                assertEquals(400,f.sendRaw("POST",accept,"{\"expectedVersion\":"+literal+"}",bearer(f.adminToken)).status(),literal);
            for(String literal:List.of(id,id+".0","true")){
                String bad="{\"expectedVersion\":\"0\",\"supplementRequestId\":"+literal+",\"text\":\"Independent additional evidence\",\"evidenceAssetIds\":[]}";
                assertEquals(400,f.sendRaw("POST","/c/aftersales/"+id+"/evidence",bad,bearer(f.buyerToken)).status(),literal);
            }
            assertEquals(beforeInvalid,f.count("SELECT COUNT(*) FROM aftersale_command"));
            assertEquals(409,f.send("POST",accept,Map.of("expectedVersion","1"),f.adminToken).status());
            receipt=accept(f,receipt);long commands=f.count("SELECT COUNT(*) FROM aftersale_command"),decisions=f.count("SELECT COUNT(*) FROM aftersale_decision");
            for(String literal:List.of("32.00","32","true","false")){
                String bad="{\"expectedVersion\":\""+receipt.value("version")+"\",\"decisionType\":\"PARTIAL_REFUND\",\"refundAmount\":"+literal+",\"reason\":\"Public monetary decision must reject non string amounts\"}";
                assertEquals(400,f.sendRaw("POST",adminPath(id,"decisions"),bad,bearer(f.adminToken)).status(),literal);
            }
            for(String type:List.of("FULL_REFUND","PARTIAL_REFUND")){
                var money=Map.of("expectedVersion",receipt.value("version"),"decisionType",type,"refundAmount",type.equals("FULL_REFUND")?"128.00":"32.00","reason","HTTP must not authorize disbursement");
                assertEquals(503,f.send("POST",adminPath(id,"decisions"),money,f.adminToken).status());
            }
            assertEquals(commands,f.count("SELECT COUNT(*) FROM aftersale_command"));assertEquals(decisions,f.count("SELECT COUNT(*) FROM aftersale_decision"));assertNoMoney(f);
            assertEquals("RESOLVED",decide(f,receipt,"OTHER").value("status"));
        }
    }

    @Test void currentSessionsPartyScopesFrozenAccountsAndActionRevocationApplyToReplay()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder();var receipt=f.send("POST",createPath(order),createBody(),f.buyerToken);assertEquals(201,receipt.status());String id=receipt.value("afterSaleId");
            assertEquals(401,f.send("GET","/c/aftersales/"+id,null,(String)null).status());
            assertEquals(401,f.send("GET","/admin/aftersales/"+id,null,f.buyerToken).status());
            assertEquals(403,f.send("GET","/c/aftersales/"+id,null,f.otherToken).status());
            assertEquals(403,f.send("GET","/c/aftersales/"+id,null,f.ownerToken).status());
            assertEquals(403,f.send("GET","/merchant/aftersales/"+id,null,f.buyerToken).status());
            assertEquals(0,((Number)f.send("GET","/c/aftersales",null,f.otherToken).data().get("total")).intValue());
            assertEquals(403,f.send("GET","/c/orders/"+order+"/aftersale-eligibility",null,f.otherToken).status());
            for(String party:List.of("merchant","admin")){
                assertEquals(400,f.send("GET","/"+party+"/aftersales",null,token(f,party)).status());
                assertEquals(400,f.send("GET","/"+party+"/aftersales?merchantId="+MERCHANT+"&storeId="+STORE+"&cityCode=chengdu",null,token(f,party)).status());
            }
            long transitions=f.count("SELECT COUNT(*) FROM aftersale_transition"),events=f.count("SELECT COUNT(*) FROM integration_event_outbox");
            // The independent raw DataSource commits the real account freeze after the business
            // transition/outbox writes but before AFS beforeCommit. It does not forge a session.
            f.afterProgressPublished.set(()->f.jdbc.update("UPDATE user_account SET status='FROZEN' WHERE id=710100"));
            var raced=f.send("POST","/c/aftersales/"+id+"/withdraw",Map.of("expectedVersion",receipt.value("version")),f.buyerToken);
            assertEquals(403,raced.status());assertNull(f.afterProgressPublished.get(),"the actual progress publish point must have been reached");
            assertEquals("PENDING",f.text("SELECT status FROM aftersale_case"));assertEquals(0,f.count("SELECT version FROM aftersale_case"));
            assertEquals(id,f.text("SELECT CAST(current_aftersale_id AS CHAR) FROM pet_order"));
            assertEquals(transitions,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(events,f.count("SELECT COUNT(*) FROM integration_event_outbox"));
            f.sql("UPDATE user_account SET status='FROZEN' WHERE id=710100");
            assertEquals(200,f.send("GET","/c/aftersales/"+id,null,f.buyerToken).status());
            assertEquals(403,f.send("POST","/c/aftersales/"+id+"/withdraw",Map.of("expectedVersion",receipt.value("version")),f.buyerToken).status());
            f.sql("UPDATE user_account SET status='ACTIVE' WHERE id=710100");
            f.sql("UPDATE merchant_store SET status='FROZEN' WHERE id=710302");
            assertEquals(200,f.send("GET","/merchant/aftersales/"+id,null,f.ownerToken).status());
            assertEquals(403,f.send("POST","/merchant/aftersales/"+id+"/opinion",Map.of("expectedVersion",receipt.value("version"),"opinionCode","AGREE","explanation","Frozen owners cannot submit","evidenceAssetIds",List.of()),f.ownerToken).status());
            f.sql("UPDATE merchant_store SET status='ACTIVE' WHERE id=710302");
            var acceptBody=Map.of("expectedVersion",receipt.value("version"));var acceptHeaders=bearer(f.adminToken);
            receipt=f.send("POST",adminPath(id,"accept"),acceptBody,acceptHeaders);assertEquals(200,receipt.status());
            assertEquals(receipt.data(),f.send("POST",adminPath(id,"accept"),acceptBody,acceptHeaders).data());
            configureAdmin(f,Set.of("aftersale.read"),"ALL",null);
            assertEquals(200,f.send("GET","/admin/aftersales/"+id,null,f.adminToken).status());
            assertEquals(403,f.send("POST",adminPath(id,"accept"),acceptBody,acceptHeaders).status());
            configureAdmin(f,Set.of("aftersale.read"),"CITY","outside-city");
            assertEquals(403,f.send("GET","/admin/aftersales/"+id,null,f.adminToken).status());
            assertEquals(403,f.send("GET","/admin/aftersales?merchantId="+MERCHANT+"&storeId="+STORE+"&status=CLOSED",null,f.adminToken).status());
            configureAdmin(f,Set.of("aftersale.read"),"CITY","chengdu");
            assertEquals(200,f.send("GET","/admin/aftersales/"+id,null,f.adminToken).status());
            String old=f.buyerToken;f.loginBuyer();
            // Contract10 MINIAPP logout revokes the supplied current session. A new C login
            // does not carry ADMIN_WEB's account-generation / kick-old-session semantics.
            assertEquals(200,f.send("GET","/c/aftersales/"+id,null,old).status());
            assertEquals(200,f.send("POST","/c/auth/logout",Map.of(),old).status());
            assertEquals(401,f.send("GET","/c/aftersales/"+id,null,old).status());
            assertEquals(200,f.send("GET","/c/aftersales/"+id,null,f.buyerToken).status());
            assertNoMoney(f);
        }
    }

    @Test void realOrdinaryRejectionAndListValidationDoNotAcceptCallerInventedEligibility()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.rejectedOrder();assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_decision WHERE status='REJECTED'"));
            var receipt=f.send("POST",createPath(order),createBody(),f.buyerToken);assertEquals(201,receipt.status());
            assertEquals("UNVERIFIED_POST_START",f.send("GET","/c/aftersales/"+receipt.value("afterSaleId"),null,f.buyerToken).value("sourceStage"));
            for(String query:List.of("page=0","page=10001","pageSize=0","pageSize=51","page=1.0","page=1&page=2","status=not-a-status","orderId=01","unexpected=true"))
                assertEquals(400,f.send("GET","/c/aftersales?"+query,null,f.buyerToken).status(),query);
            var filtered=f.send("GET","/c/aftersales?page=1&pageSize=1&status=PENDING&orderId="+order,null,f.buyerToken);
            assertEquals(200,filtered.status());assertEquals(1,((Number)filtered.data().get("total")).intValue());
            assertEquals(200,f.send("GET","/c/aftersales?page=10000&pageSize=50",null,f.buyerToken).status());
            assertNoMoney(f);
        }
    }

    static AfterSaleHttpFixture.Reply accept(AfterSaleHttpFixture f,AfterSaleHttpFixture.Reply current)throws Exception{
        String id=current.value("afterSaleId");var detail=f.send("GET","/admin/aftersales/"+id,null,f.adminToken);assertEquals(200,detail.status());
        var body=new LinkedHashMap<String,Object>();body.put("expectedVersion",current.value("version"));
        if(!((List<?>)detail.data().get("priorFinalCaseIds")).isEmpty()){
            body.put("expectedFinalSetVersion",detail.value("finalSetVersion"));body.put("newProblemAssessment","Compared all prior decisions and confirmed a distinct new problem");
        }
        var result=f.send("POST",adminPath(id,"accept"),body,f.adminToken);assertEquals(200,result.status(),result.toString());return result;
    }
    static AfterSaleHttpFixture.Reply decide(AfterSaleHttpFixture f,AfterSaleHttpFixture.Reply current,String type)throws Exception{
        var result=f.send("POST",adminPath(current.value("afterSaleId"),"decisions"),Map.of("expectedVersion",current.value("version"),"decisionType",type,"reason","Reviewed all evidence and determined the final non monetary outcome"),f.adminToken);
        assertEquals(200,result.status(),result.toString());assertNull(result.data().get("refundOrderId"));return result;
    }
    static String createPath(String order){return "/c/orders/"+order+"/aftersales";}
    static String adminPath(String id,String suffix){return "/admin/aftersales/"+id+"/"+suffix;}
    static String token(AfterSaleHttpFixture f,String party){return switch(party){case "c"->f.buyerToken;case "merchant"->f.ownerToken;case "admin"->f.adminToken;default->throw new IllegalArgumentException();};}
    static void assertPublicReceipt(AfterSaleHttpFixture.Reply receipt){
        for(String key:List.of("commandId","orderId","afterSaleId","version"))assertInstanceOf(String.class,receipt.data().get(key));
        assertTrue(receipt.value("occurredAt").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"));
    }
    static void assertNoMoney(AfterSaleHttpFixture f){
        for(String table:List.of("refund_order","refund_execution","refund_aftersale_proof","order_aftersale_refund_commit","payment_refund_dispatch","payment_refund_funding_proof"))assertEquals(0,f.count("SELECT COUNT(*) FROM "+table),table);
        assertEquals(0,f.channelCalls.get());
    }
    static void configureAdmin(AfterSaleHttpFixture f,Set<String> actions,String mode,String city){
        String actor=f.context.getBean(AdminAuthService.class).resolveSession(f.adminToken).principal().operatorId();
        // Authoritative RBAC configuration/revocation fixture; no fabricated authorization decision or session.
        var tx=new TransactionTemplate(new DataSourceTransactionManager(f.ordinary.source));tx.executeWithoutResult(ignored->{
            f.jdbc.queryForObject("SELECT revision FROM admin_authz_revision WHERE id=1 FOR UPDATE",Long.class);
            f.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?",actor);f.jdbc.update("DELETE FROM admin_extra_grant WHERE account_id=?",actor);
            for(String action:actions)f.jdbc.update("INSERT INTO admin_extra_grant(account_id,action_code,granted_by,granted_at) VALUES(?,?,?,UTC_TIMESTAMP(3))",actor,action,actor);
            f.jdbc.update("DELETE FROM admin_scope_city WHERE account_id=?",actor);f.jdbc.update("DELETE FROM admin_scope_merchant WHERE account_id=?",actor);
            f.jdbc.update("UPDATE admin_account_scope SET mode=?,version=version+1 WHERE account_id=?",mode,actor);
            if(city!=null)f.jdbc.update("INSERT INTO admin_scope_city(account_id,city_code) VALUES(?,?)",actor,city);
            f.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
        });
    }
}
