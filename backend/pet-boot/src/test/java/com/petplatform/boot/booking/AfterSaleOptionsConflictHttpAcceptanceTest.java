package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static com.petplatform.boot.booking.AfterSaleHttpAcceptanceTest.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Real loopback HTTP, production catalog binding, actual sessions and transactional SQL. */
class AfterSaleOptionsConflictHttpAcceptanceTest {
    @Test void explicitlyClosedHttpHasNoOptionsMappingEvenWithCurrentSession()throws Exception{
        try(var f=new AfterSaleHttpFixture(Map.of("pet.aftersale.http.enabled",false))){
            assertTrue(f.context.getBeansOfType(com.petplatform.boot.adapter.web.aftersale.AfterSaleController.class).isEmpty());
            var response=f.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(f.baseUrl+"/c/aftersale-options"))
                    .header("Authorization","Bearer "+f.buyerToken).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(403,response.statusCode());
            @SuppressWarnings("unchecked") var denial=f.json.readValue(response.body(),Map.class);
            assertEquals(Set.of("code","message","data","traceId"),denial.keySet());assertEquals("COMMON_FORBIDDEN",denial.get("code"));assertNull(denial.get("data"));
            assertEquals("no-store, private",response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_command"));assertNoMoney(f);
        }
    }
    @Test void authenticatedCatalogIsSortedStrictAndUsesExactlyTheNewCreateCodes()throws Exception{
        try(var f=new AfterSaleHttpFixture(Map.of("pet.aftersale.type-codes","QA_QUALITY,QA_A_ISSUE",
                "pet.aftersale.type-labels[QA_A_ISSUE]","QA alternate issue"))){
            assertEquals(401,f.send("GET","/c/aftersale-options",null,(String)null).status());
            assertEquals(401,f.send("GET","/c/aftersale-options",null,f.adminToken).status());
            var response=f.send("GET","/c/aftersale-options",null,f.buyerToken);assertEquals(200,response.status());
            assertEquals(Set.of("typeOptions","demandOptions"),response.data().keySet());
            assertEquals(List.of(Map.of("code","QA_A_ISSUE","label","QA alternate issue"),Map.of("code","QA_QUALITY","label","QA quality issue")),response.data().get("typeOptions"));
            assertEquals(List.of(Map.of("code","QA_REFUND","label","QA requested resolution")),response.data().get("demandOptions"));
            assertEquals("no-store, private",response.headers().firstValue("Cache-Control").orElseThrow());
            for(String query:List.of("?page=1","?userId="+OTHER,"?page=1&page=1"))
                assertEquals(400,f.send("GET","/c/aftersale-options"+query,null,f.buyerToken).status(),query);
            // Java HttpClient normalizes an empty query away. Preserve the literal wire target
            // to test the approved rule, rather than accidentally sending a query-free GET.
            for(String query:List.of("?","?&&"))assertEquals(400,wireGetStatus(f,"/c/aftersale-options"+query,f.buyerToken),query);
            for(String body:List.of("{}"," ","null"))
                assertEquals(400,f.sendRaw("GET","/c/aftersale-options",body,bearer(f.buyerToken)).status());
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_command"));
            String order=f.verifiedOrder();
            for(String type:List.of("QA_A_ISSUE","QA_QUALITY")){
                var body=createBody();body.put("typeCode",type);var receipt=f.send("POST",createPath(order),body,f.buyerToken);assertEquals(201,receipt.status());
                var detail=f.send("GET","/c/aftersales/"+receipt.value("afterSaleId"),null,f.buyerToken);assertEquals(type,detail.value("typeCode"));
                assertEquals(200,f.send("POST","/c/aftersales/"+receipt.value("afterSaleId")+"/withdraw",Map.of("expectedVersion",receipt.value("version")),f.buyerToken).status());
            }
            long cases=f.count("SELECT COUNT(*) FROM aftersale_case"),commands=f.count("SELECT COUNT(*) FROM aftersale_command");
            for(String type:List.of("QA_UNKNOWN","QA alternate issue")){
                var body=createBody();body.put("typeCode",type);assertEquals(400,f.send("POST",createPath(order),body,f.buyerToken).status());
            }
            assertEquals(cases,f.count("SELECT COUNT(*) FROM aftersale_case"));assertEquals(commands,f.count("SELECT COUNT(*) FROM aftersale_command"));
            f.sql("UPDATE user_account SET status='FROZEN' WHERE id=710100");
            assertEquals(200,f.send("GET","/c/aftersale-options",null,f.buyerToken).status());
            assertEquals(403,f.send("POST",createPath(order),createBody(),f.buyerToken).status());
            f.sql("UPDATE user_account SET status='ACTIVE' WHERE id=710100");
            assertEquals(200,f.send("POST","/c/auth/logout",Map.of(),f.buyerToken).status());
            assertEquals(401,f.send("GET","/c/aftersale-options",null,f.buyerToken).status());assertNoMoney(f);
        }
    }

    private static int wireGetStatus(AfterSaleHttpFixture f,String path,String token)throws Exception{
        var base=java.net.URI.create(f.baseUrl);
        try(var socket=new java.net.Socket()){
            socket.connect(new java.net.InetSocketAddress("127.0.0.1",base.getPort()),5000);socket.setSoTimeout(20000);
            String request="GET "+base.getRawPath()+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+base.getPort()+"\r\nAuthorization: Bearer "+token+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            String response=new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
            String status=response.split("\r\n",2)[0];assertTrue(status.matches("HTTP/1\\.[01] [0-9]{3}.*"),"A literal HTTP request target must receive an HTTP response");
            return Integer.parseInt(status.substring(9,12));
        }
    }

    @Test void incompleteLabelConfigurationClosesCatalogAndCreateBeforeAdmission()throws Exception{
        try(var f=new AfterSaleHttpFixture(Map.of("pet.aftersale.type-labels[QA_QUALITY]",""))){
            var options=f.send("GET","/c/aftersale-options",null,f.buyerToken);assertEquals(503,options.status());assertEquals("COMMON_DEPENDENCY_UNAVAILABLE",options.envelope().get("code"));
            var receipt=f.send("POST",createPath(f.verifiedOrder()),createBody(),f.buyerToken);assertEquals(503,receipt.status());
            assertEquals("COMMON_DEPENDENCY_UNAVAILABLE",receipt.envelope().get("code"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_command"));assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_case"));assertNoMoney(f);
        }
    }

    @Test void simultaneousSameVersionLoserHasDefiniteCodeNoBusinessEffectsAndWinnerReplaysOriginalReceipt()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            var created=f.send("POST",createPath(f.verifiedOrder()),createBody(),f.buyerToken);assertEquals(201,created.status());String id=created.value("afterSaleId");
            String path="/c/aftersales/"+id+"/evidence";var body=Map.of("expectedVersion","0","text","Concurrent independently described additional facts","evidenceAssetIds",List.of());
            var headers=List.of(bearer(f.buyerToken),bearer(f.buyerToken));
            long batches=f.count("SELECT COUNT(*) FROM aftersale_evidence_batch"),transitions=f.count("SELECT COUNT(*) FROM aftersale_transition"),events=f.count("SELECT COUNT(*) FROM integration_event_outbox"),logs=f.count("SELECT COUNT(*) FROM aftersale_status_log"),orderVersion=f.count("SELECT version FROM pet_order");
            var start=new CountDownLatch(1);List<Reply> replies;
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
                var tasks=new ArrayList<Future<Reply>>();for(var h:headers)tasks.add(executor.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return f.send("POST",path,body,h);}));
                start.countDown();replies=List.of(tasks.get(0).get(30,TimeUnit.SECONDS),tasks.get(1).get(30,TimeUnit.SECONDS));
            }
            assertEquals(List.of(200,409),replies.stream().map(Reply::status).sorted().toList());
            int winner=replies.get(0).status()==200?0:1,loser=1-winner;assertEquals("AFTERSALE_VERSION_CONFLICT",replies.get(loser).envelope().get("code"));
            assertEquals(batches+1,f.count("SELECT COUNT(*) FROM aftersale_evidence_batch"));assertEquals(transitions+1,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(events+1,f.count("SELECT COUNT(*) FROM integration_event_outbox"));
            assertEquals(logs+1,f.count("SELECT COUNT(*) FROM aftersale_status_log"));assertEquals(1,f.count("SELECT version FROM aftersale_case"));assertEquals(orderVersion+1,f.count("SELECT version FROM pet_order"));
            assertEquals(id,f.text("SELECT CAST(current_aftersale_id AS CHAR) FROM pet_order"));assertEquals("PENDING",f.text("SELECT aftersale_status FROM pet_order"));
            var repeatLoser=f.send("POST",path,body,headers.get(loser));assertEquals(409,repeatLoser.status());assertEquals("AFTERSALE_VERSION_CONFLICT",repeatLoser.envelope().get("code"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_transition WHERE command_id=(SELECT id FROM aftersale_command WHERE request_id=CAST('"+headers.get(loser).get("X-Request-Id")+"' AS BINARY))"));
            assertEquals(logs+1,f.count("SELECT COUNT(*) FROM aftersale_status_log"));assertEquals(transitions+1,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(events+1,f.count("SELECT COUNT(*) FROM integration_event_outbox"));
            assertEquals(200,f.send("POST","/merchant/aftersales/"+id+"/opinion",Map.of("expectedVersion","1","opinionCode","AGREE","explanation","Merchant reviewed the newly added evidence independently","evidenceAssetIds",List.of()),f.ownerToken).status());
            var replay=f.send("POST",path,body,headers.get(winner));assertEquals(200,replay.status());assertEquals(replies.get(winner).data(),replay.data());assertEquals("1",replay.value("version"));
            assertEquals(logs+2,f.count("SELECT COUNT(*) FROM aftersale_status_log"));assertEquals(transitions+2,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(events+2,f.count("SELECT COUNT(*) FROM integration_event_outbox"));assertEquals(batches+2,f.count("SELECT COUNT(*) FROM aftersale_evidence_batch"));
            var changed=new LinkedHashMap<String,Object>(body);changed.put("expectedVersion","2");
            assertEquals("IDEMPOTENCY_KEY_CONFLICT",f.send("POST",path,changed,headers.get(loser)).envelope().get("code"));
            f.sql("UPDATE merchant_store SET status='FROZEN' WHERE id=710302");
            var frozen=f.send("POST","/merchant/aftersales/"+id+"/opinion",Map.of("expectedVersion","0","opinionCode","AGREE","explanation","Frozen writer cannot get a version rejection as authorization","evidenceAssetIds",List.of()),f.ownerToken);
            assertEquals(403,frozen.status());assertEquals("COMMON_FORBIDDEN",frozen.envelope().get("code"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_decision"));assertNoMoney(f);
        }
    }

    @Test void concurrentRbacMutationWaitsForCaseCommitThenBlocksOriginalSuccessReplay()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            var created=f.send("POST",createPath(f.verifiedOrder()),createBody(),f.buyerToken);assertEquals(201,created.status());String id=created.value("afterSaleId");
            long transitions=f.count("SELECT COUNT(*) FROM aftersale_transition"),logs=f.count("SELECT COUNT(*) FROM aftersale_status_log"),events=f.count("SELECT COUNT(*) FROM integration_event_outbox"),orderVersion=f.count("SELECT version FROM pet_order");
            String actor=f.context.getBean(com.petplatform.admin.biz.application.AdminAuthService.class).resolveSession(f.adminToken).principal().operatorId();
            var writer=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();var started=new CountDownLatch(1);
            f.afterProgressPublished.set(()->{
                var future=CompletableFuture.runAsync(()->{
                    // Actual independent RBAC transaction, using one bound DataSource throughout.
                    // EXECUTE's revision row lock joins AFS, so this writer must wait for commit.
                    var db=new org.springframework.jdbc.core.JdbcTemplate(f.ordinary.source);
                    var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.ordinary.source));
                    tx.executeWithoutResult(s->{started.countDown();db.queryForObject("SELECT revision FROM admin_authz_revision WHERE id=1 FOR UPDATE",Long.class);
                        db.update("DELETE FROM admin_account_role WHERE account_id=?",actor);db.update("DELETE FROM admin_extra_grant WHERE account_id=?",actor);
                        db.update("INSERT INTO admin_extra_grant(account_id,action_code,granted_by,granted_at) VALUES(?,'aftersale.read',?,UTC_TIMESTAMP(3))",actor,actor);
                        db.update("UPDATE admin_account_scope SET mode='ALL',version=version+1 WHERE account_id=?",actor);
                        db.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");});
                });
                writer.set(future);
                try{assertTrue(started.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                assertFalse(future.isDone(),"RBAC mutation must not commit while AFS holds the authorization rows");
            });
            var body=Map.of("expectedVersion","0");var headers=bearer(f.adminToken);
            try {
                var accepted=f.send("POST",adminPath(id,"accept"),body,headers);assertEquals(200,accepted.status());assertEquals("PROCESSING",accepted.value("status"));
                writer.get().get(20,TimeUnit.SECONDS);assertNull(f.afterProgressPublished.get());
                assertEquals(403,f.send("POST",adminPath(id,"accept"),body,headers).status());
                assertEquals("PROCESSING",f.text("SELECT status FROM aftersale_case"));assertEquals(1,f.count("SELECT version FROM aftersale_case"));assertEquals(orderVersion+1,f.count("SELECT version FROM pet_order"));
                assertEquals(transitions+1,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(logs+1,f.count("SELECT COUNT(*) FROM aftersale_status_log"));assertEquals(events+1,f.count("SELECT COUNT(*) FROM integration_event_outbox"));assertNoMoney(f);
            }finally{var future=writer.get();if(future!=null&&!future.isDone())future.get(20,TimeUnit.SECONDS);}
        }
    }

    @Test void faultInjectionOfRevisionWithinSameCaseTransactionIsGenericConflictAndRollsBack()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            var created=f.send("POST",createPath(f.verifiedOrder()),createBody(),f.buyerToken);assertEquals(201,created.status());String id=created.value("afterSaleId");
            long transitions=f.count("SELECT COUNT(*) FROM aftersale_transition"),logs=f.count("SELECT COUNT(*) FROM aftersale_status_log"),events=f.count("SELECT COUNT(*) FROM integration_event_outbox"),orderVersion=f.count("SELECT version FROM pet_order"),revision=f.count("SELECT revision FROM admin_authz_revision WHERE id=1");
            f.afterProgressPublished.set(()->{
                // FAULT_INJECTION: deliberately alter the already-locked revision in this exact
                // AFS transaction. This is not an external RBAC commit or a concurrent revocation.
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertNotNull(org.springframework.transaction.support.TransactionSynchronizationManager.getResource(f.ordinary.source));
                var bound=new org.springframework.jdbc.core.JdbcTemplate(f.ordinary.source);
                assertEquals(1,bound.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1"));
            });
            var failure=f.send("POST",adminPath(id,"accept"),Map.of("expectedVersion","0"),f.adminToken);
            assertEquals(409,failure.status());assertEquals("COMMON_CONFLICT",failure.envelope().get("code"));assertNull(f.afterProgressPublished.get());
            assertEquals("PENDING",f.text("SELECT status FROM aftersale_case"));assertEquals(0,f.count("SELECT version FROM aftersale_case"));assertEquals(orderVersion,f.count("SELECT version FROM pet_order"));
            assertEquals(revision,f.count("SELECT revision FROM admin_authz_revision WHERE id=1"));assertEquals(transitions,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(logs,f.count("SELECT COUNT(*) FROM aftersale_status_log"));assertEquals(events,f.count("SELECT COUNT(*) FROM integration_event_outbox"));assertNoMoney(f);
        }
    }

    @Test void finalSetMismatchRejectsBeforeEffectsMissingProofIs400AndFreshUuidCanAccept()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder();var first=f.send("POST",createPath(order),createBody(),f.buyerToken);assertEquals(201,first.status());
            String oldSet=f.send("GET","/admin/aftersales/"+first.value("afterSaleId"),null,f.adminToken).value("finalSetVersion");
            first=decide(f,accept(f,first),"OTHER");var create=createBody();create.put("newProblemStatement","A separately noticed problem after the prior conclusion");
            var current=f.send("POST",createPath(order),create,f.buyerToken);assertEquals(201,current.status());String id=current.value("afterSaleId"),path=adminPath(id,"accept");
            var detail=f.send("GET","/admin/aftersales/"+id,null,f.adminToken);assertNotEquals(oldSet,detail.value("finalSetVersion"));
            long transitions=f.count("SELECT COUNT(*) FROM aftersale_transition"),events=f.count("SELECT COUNT(*) FROM integration_event_outbox");
            String assessment="Operator compared the complete history and the newly described facts";
            assertEquals(400,f.send("POST",path,Map.of("expectedVersion","0","newProblemAssessment",assessment),f.adminToken).status());
            assertEquals(400,f.send("POST",path,Map.of("expectedVersion","0","expectedFinalSetVersion",oldSet),f.adminToken).status());
            var stale=Map.of("expectedVersion","0","newProblemAssessment",assessment,"expectedFinalSetVersion",oldSet);var staleHeaders=bearer(f.adminToken);
            for(int n=0;n<2;n++){var failed=f.send("POST",path,stale,staleHeaders);assertEquals(409,failed.status());assertEquals("AFTERSALE_FINAL_SET_CONFLICT",failed.envelope().get("code"));}
            assertEquals(transitions,f.count("SELECT COUNT(*) FROM aftersale_transition"));assertEquals(events,f.count("SELECT COUNT(*) FROM integration_event_outbox"));
            assertEquals("PENDING",f.send("GET","/admin/aftersales/"+id,null,f.adminToken).value("status"));assertEquals(1,f.count("SELECT COUNT(*) FROM aftersale_decision"));
            var fresh=new LinkedHashMap<String,Object>(stale);fresh.put("expectedFinalSetVersion",detail.value("finalSetVersion"));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT",f.send("POST",path,fresh,staleHeaders).envelope().get("code"));
            var accepted=f.send("POST",path,fresh,f.adminToken);assertEquals(200,accepted.status());assertEquals("PROCESSING",accepted.value("status"));assertNoMoney(f);
        }
    }
}
