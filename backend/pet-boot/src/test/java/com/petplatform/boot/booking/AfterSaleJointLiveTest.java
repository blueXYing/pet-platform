package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static com.petplatform.boot.booking.AfterSaleHttpAcceptanceTest.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.common.*;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.verification.api.command.VerificationCredentialApi;
import com.petplatform.verification.api.command.VerificationCompletionApi;
import com.petplatform.verification.biz.application.VerificationCredentialService;
import com.petplatform.verification.biz.application.VerificationCompletionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/** Opt-in local UI joint environment. This class and its secret control server are test-only.
 * Business results are created by real APIs, not INSERTed. WeChat, signed offline payment
 * receipts, moderation, scanner and in-memory OSS remain explicitly isolated providers.
 * A passing lifecycle is not evidence that a human/device completed any UI flow. */
@EnabledIfEnvironmentVariable(named="AFS_JOINT_LIVE",matches="true")
class AfterSaleJointLiveTest {
    @Test void keepRealBackendAvailableUntilExplicitStop() throws Exception {
        Path directory=Path.of(Objects.requireNonNull(System.getenv("AFS_JOINT_RUNTIME_DIR"))).toAbsolutePath();
        assertTrue(directory.toString().replace('\\','/').endsWith("/backend/pet-boot/target/aftersale-joint"));
        Files.createDirectories(directory);
        Path runtime=directory.resolve("runtime.json"),stop=directory.resolve("stop");
        Files.deleteIfExists(stop);Files.deleteIfExists(runtime);
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put("server.address","127.0.0.1");
        properties.put("pet.auth.admin.origin","http://127.0.0.1:4174");
        properties.put("pet.auth.c.access-ttl-seconds",7200);
        properties.put("spring.config.import","classpath:aftersale-catalog.yml");
        properties.put("pet.merchant.application.enabled",true);
        properties.put("pet.merchant.application.open-cities[0].code","chengdu");
        properties.put("pet.merchant.application.open-cities[0].name","成都");
        properties.put("pet.merchant.protection.enabled",true);properties.put("pet.merchant.subject.enabled",true);
        properties.put("MERCHANT_PROTECTED_KEY_VERSION","qa-joint");properties.put("MERCHANT_SUBJECT_POLICY_VERSION","qa-v1");
        properties.put("MERCHANT_PROTECTED_AES_KEY_BASE64",key(41));properties.put("MERCHANT_PROTECTED_HMAC_KEY_BASE64",key(43));
        properties.put("MERCHANT_SUBJECT_HMAC_KEY_BASE64",key(47));
        try(var f=new AfterSaleHttpFixture(properties,false);var control=new Control(f,stop)){
            provisionIndependentCapacity(f);
            control.permissions("FULL_AFS");
            // Prepare every independent lane before any completion: the inherited booking
            // clock is wall time and must not read a completed 2030 reservation as future.
            f.at(Instant.now());f.loginBuyer();f.loginOwner();
            List<String> orders=new ArrayList<>();for(int lane=0;lane<4;lane++)orders.add(readyOrder(f));
            f.at(Instant.parse("2030-01-01T10:30:01Z"));f.loginBuyer();f.loginOwner();f.loginAdmin();
            for(String order:orders)verifyOrder(f,order);
            var memberships=f.send("GET","/c/auth/merchant-memberships?page=1&pageSize=20",null,f.ownerToken);assertEquals(200,memberships.status());
            var admission=f.send("GET","/merchant/auth/admission?merchantId="+MERCHANT+"&storeId="+STORE,null,f.ownerToken);assertEquals(200,admission.status());assertEquals("ALLOWED",admission.value("admission"));
            Map<String,Object> cases=new LinkedHashMap<>();
            String p4Order=orders.get(0);List<String> finals=new ArrayList<>();
            for(String type:List.of("REJECT","RESERVICE","OTHER")){
                var current=create(f,p4Order,!finals.isEmpty(),null);
                current=accept(f,current);current=decide(f,current,type);finals.add(current.value("afterSaleId"));
            }
            var p4=create(f,p4Order,true,uploadPicture(f));
            cases.put("p4CaseId",p4.value("afterSaleId"));cases.put("priorFinalIds",List.copyOf(finals));
            cases.put("p4OrderId",p4Order);
            String workflowOrder=orders.get(1);String asset=uploadPicture(f);
            var workflow=create(f,workflowOrder,false,asset);
            cases.put("workflowCaseId",workflow.value("afterSaleId"));cases.put("workflowOrderId",workflowOrder);
            cases.put("evidence",Map.of("assetId",asset,"batchId",workflow.value("evidenceBatchId")));
            String recoveryOrder=orders.get(2);var recovery=create(f,recoveryOrder,false,uploadPicture(f));
            cases.put("recoveryCaseId",recovery.value("afterSaleId"));cases.put("recoveryOrderId",recoveryOrder);
            String miniOrder=orders.get(3);
            cases.put("miniOrderId",miniOrder);
            assertNoMoney(f);
            assertEquals(3,((List<?>)f.send("GET","/admin/aftersales/"+p4.value("afterSaleId"),null,f.adminToken).data().get("priorFinalCaseIds")).size());
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("fixtureOnly",true);payload.put("backendOrigin",URI.create(f.baseUrl).resolve("/").toString().replaceAll("/$",""));
            payload.put("baseUrl",f.baseUrl);payload.put("clockInstant",f.clock.instant().toString());
            payload.put("admin",Map.of("account","qa-http-operator","password",PASSWORD,"operatorId",control.actor,"accessToken",f.adminToken));
            payload.put("buyer",userRuntime(f,BUYER,"13800000100",f.buyerToken));
            payload.put("owner",userRuntime(f,OWNER,"13800000300",f.ownerToken));
            payload.put("scope",Map.of("merchantId",MERCHANT,"storeId",STORE));
            payload.put("cases",cases);payload.put("mini",Map.of("orderId",miniOrder,"fixtureOnly",true));
            payload.put("control",Map.of("baseUrl",control.baseUrl,"secret",control.secret));
            payload.put("catalog",f.send("GET","/c/aftersale-options",null,f.buyerToken).data());
            payload.put("externalFixtureProviders",List.of("WECHAT_EXCHANGE","SIGNED_OFFLINE_PAYMENT_RECEIPT","CONTENT_MODERATION","ANTIVIRUS_SCANNER","IN_MEMORY_PRIVATE_OSS"));
            payload.put("initialSummary",summary(f,null));
            Files.writeString(directory.resolve("runtime.tmp"),f.json.writeValueAsString(payload),StandardCharsets.UTF_8);
            Files.move(directory.resolve("runtime.tmp"),runtime,StandardCopyOption.REPLACE_EXISTING);
            System.out.println("AFS joint backend READY; credentials only in ignored runtime file");
            long seconds=Long.parseLong(System.getenv().getOrDefault("AFS_JOINT_TIMEOUT_SECONDS","14400"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(!Files.exists(stop)&&System.nanoTime()<deadline)Thread.sleep(500);
            assertTrue(Files.exists(stop),"Joint environment lifetime expired without explicit stop");
            assertNoMoney(f);
            Files.writeString(directory.resolve("final-summary.json"),f.json.writeValueAsString(summary(f,null)),StandardCharsets.UTF_8);
        } finally {Files.deleteIfExists(runtime);Files.deleteIfExists(directory.resolve("runtime.tmp"));}
    }

    private static Map<String,Object> userRuntime(AfterSaleHttpFixture f,String user,String phone,String token)throws Exception{
        var session=f.send("GET","/c/auth/session",null,token);assertEquals(200,session.status());
        var grant=new LinkedHashMap<>(session.data());grant.put("accessToken",token);grant.put("tokenType","Bearer");
        return Map.of("userId",user,"phone",phone,"wechatCode","qa-http:user-"+user,"phoneCode","phone:"+phone,"grant",grant);
    }

    private static AfterSaleHttpFixture.Reply create(AfterSaleHttpFixture f,String order,boolean p4,String asset)throws Exception{
        var body=createBody();body.put("typeCode","SERVICE_QUALITY");body.put("demandCode","OTHER");
        body.put("description","QA 隔离联调：请核对本次真实服务的独立问题及证据。");
        if(p4)body.put("newProblemStatement","QA 隔离联调：这是后来发现的独立问题，请逐项核对所有历史正式终局。");
        if(asset!=null)body.put("evidenceAssetIds",List.of(asset));
        var result=f.send("POST",createPath(order),body,f.buyerToken);assertEquals(201,result.status(),result.toString());return result;
    }

    /** Static bookable QA facts only. Existing one-person capacity cannot hold independent UI lanes. */
    private static void provisionIndependentCapacity(AfterSaleHttpFixture f){
        var j=f.jdbc;
        for(int index=1;index<=8;index++){
            long staff=720000L+index;
            j.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,service_enabled,created_at,updated_at) VALUES(?,710301,710302,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",staff,"QA 联调员工 "+index);
            j.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,updated_at) VALUES(?,?,710401,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",721000L+index,staff);
            j.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,created_at,updated_at) VALUES(?,710302,?,'2030-01-01 08:00:00','2030-01-01 14:00:00','AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",722000L+index,staff);
        }
        j.update("UPDATE schedule_availability_window SET configured_capacity=9 WHERE id=710500");
    }

    /** Real booking/payment acceptance/OWNER confirmation and real credential completion.
     * Payment notice is signed offline fixture input; no production channel is contacted. */
    private static String readyOrder(AfterSaleHttpFixture f)throws Exception{
        var foundation=f.ordinary.t.r.f.f;
        String order=foundation.book().orderId();var payment=foundation.prepare(order,rid());
        var notice=foundation.notice(payment,"SUCCESS","QA_JOINT_"+payment.paymentId(),payment.amount(),payment.amount());
        foundation.notification.receive(notice.headers(),notice.body());
        var event=f.jdbc.queryForObject("SELECT * FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.orderId'))=?",
            (rs,n)->new DispatchedEvent(rs.getString("event_id"),rs.getString("event_type"),rs.getInt("event_version"),rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),rs.getString("aggregate_type"),rs.getLong("aggregate_id"),rs.getString("trace_id"),rs.getString("payload")),order);
        foundation.result.consume(event);
        // Call the BOOT authority-composed merchant command rather than the inherited fixture's session port.
        // Offline notices carry wall-clock payment time, so confirm within its real deadline.
        f.at(Instant.now());f.as(f.ownerToken);
        f.context.getBean(com.petplatform.order.api.command.MerchantOrderCommandApi.class).decide(new com.petplatform.order.api.command.MerchantOrderCommandApi.Command(f.user(OWNER),order,0,"CONFIRM",null,null,null));
        RequestContextHolder.resetRequestAttributes();return order;
    }
    private static void verifyOrder(AfterSaleHttpFixture f,String order){
        var credentials=f.context.getBean(VerificationCredentialService.class);f.as(f.buyerToken);
        var view=credentials.read(order,new QueryContext("qa-joint",OperatorType.USER,BUYER));
        var issued=credentials.issue(new VerificationCredentialApi.Issue(f.user(BUYER),order,view.credentialVersion(),"INITIAL"));
        f.as(f.ownerToken);var completed=f.context.getBean(VerificationCompletionService.class).verify(new VerificationCompletionApi.Command(f.user(OWNER),order,STORE,issued.code(),issued.credentialVersion(),true));
        assertEquals("VERIFIED",completed.resultCode());RequestContextHolder.resetRequestAttributes();
    }

    private static String uploadPicture(AfterSaleHttpFixture f)throws Exception{
        var image=new BufferedImage(120,80,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        graphics.setColor(java.awt.Color.ORANGE);graphics.fillRect(0,0,120,80);graphics.setColor(java.awt.Color.BLACK);graphics.drawString("QA ONLY",15,40);graphics.dispose();
        var png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);
        String boundary="Joint"+rid();var body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"qa.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(png.toByteArray());body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var response=f.client.send(HttpRequest.newBuilder(URI.create(f.baseUrl+"/c/aftersale-evidence-assets")).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+f.buyerToken).header("X-Request-Id",rid()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode());return f.json.readTree(response.body()).get("data").get("assetId").textValue();
    }

    private static Map<String,Object> summary(AfterSaleHttpFixture f,String caseId){
        var result=new LinkedHashMap<String,Object>();var counts=new LinkedHashMap<String,Object>();
        result.put("caseId",caseId);
        String predicate=caseId==null?"":" WHERE aftersale_id="+caseId;
        if(caseId!=null&&!caseId.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
        counts.put("commands",f.count(caseId==null?"SELECT COUNT(*) FROM aftersale_command":"SELECT COUNT(*) FROM aftersale_command WHERE scope=CONCAT('AFTERSALE:',"+caseId+") OR id=(SELECT creator_command_id FROM aftersale_case WHERE id="+caseId+")"));
        counts.put("evidenceBatches",f.count("SELECT COUNT(*) FROM aftersale_evidence_batch"+predicate));
        counts.put("decisions",f.count("SELECT COUNT(*) FROM aftersale_decision"+predicate));
        counts.put("transitions",f.count("SELECT COUNT(*) FROM aftersale_transition"+predicate));
        counts.put("statusLogs",f.count("SELECT COUNT(*) FROM aftersale_status_log"+predicate));
        counts.put("outbox",f.count("SELECT COUNT(*) FROM integration_event_outbox"+(caseId==null?"":" WHERE aggregate_type='AFTERSALE' AND aggregate_id="+caseId)));
        counts.put("refundOrders",f.count("SELECT COUNT(*) FROM refund_order"));counts.put("refundExecutions",f.count("SELECT COUNT(*) FROM refund_execution"));
        counts.put("fundings",f.count("SELECT COUNT(*) FROM payment_refund_funding_proof"));
        counts.put("refundAfterSaleProofs",f.count("SELECT COUNT(*) FROM refund_aftersale_proof"));counts.put("orderRefundCommits",f.count("SELECT COUNT(*) FROM order_aftersale_refund_commit"));
        counts.put("paymentDispatches",f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
        result.put("counts",counts);result.put("channelCalls",f.channelCalls.get());
        if(caseId!=null){
            long id=Long.parseLong(caseId);
            var current=f.jdbc.queryForMap("SELECT a.status,CAST(a.version AS CHAR) AS version,CAST(o.version AS CHAR) AS orderVersion,CAST(o.current_aftersale_id AS CHAR) AS currentAfterSaleId FROM pet_order o JOIN aftersale_case a ON a.order_id=o.id WHERE a.id=?",id);
            result.putAll(current);
        }
        return result;
    }

    /** Deliberate fixture controls never registered with Spring or production sources. */
    private static final class Control implements AutoCloseable {
        final AfterSaleHttpFixture f;final Path stop;final String actor,secret,baseUrl;
        final HttpServer server;final ExecutorService executor=Executors.newSingleThreadExecutor();
        Control(AfterSaleHttpFixture fixture,Path stopFile)throws Exception{
            f=fixture;stop=stopFile;actor=f.context.getBean(AdminAuthService.class).resolveSession(f.adminToken).principal().operatorId();
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",this::handle);server.start();
            baseUrl="http://127.0.0.1:"+server.getAddress().getPort();
        }
        void permissions(String mode){
            Set<String> actions=switch(mode){case "READ_ONLY"->Set.of("aftersale.read");case "FULL_AFS"->Set.of("aftersale.read","aftersale.handle","aftersale.decide");default->throw new IllegalArgumentException();};
            var j=new JdbcTemplate(f.ordinary.source);var tx=new TransactionTemplate(new DataSourceTransactionManager(f.ordinary.source));
            tx.executeWithoutResult(ignored->{
                j.queryForObject("SELECT revision FROM admin_authz_revision WHERE id=1 FOR UPDATE",Long.class);
                j.update("DELETE FROM admin_account_role WHERE account_id=?",actor);j.update("DELETE FROM admin_extra_grant WHERE account_id=?",actor);
                for(String action:actions)j.update("INSERT INTO admin_extra_grant(account_id,action_code,granted_by,granted_at) VALUES(?,?,?,UTC_TIMESTAMP(3))",actor,action,actor);
                j.update("UPDATE admin_account_scope SET mode='ALL',version=version+1 WHERE account_id=?",actor);
                j.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
            });
        }
        private void handle(HttpExchange exchange)throws java.io.IOException{
            try(exchange){
                String supplied=exchange.getRequestHeaders().getFirst("X-Joint-Control");
                if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()||supplied==null||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.UTF_8))){reply(exchange,403,Map.of("code","FORBIDDEN"));return;}
                try{
                    String path=exchange.getRequestURI().getPath();Object data;
                    if(exchange.getRequestMethod().equals("GET")&&path.equals("/summary")){
                        String query=exchange.getRequestURI().getRawQuery();String id=null;
                        if(query!=null){if(!query.matches("caseId=[1-9][0-9]{0,18}"))throw new IllegalArgumentException();id=query.substring(7);}
                        data=summary(f,id);
                    }else if(exchange.getRequestMethod().equals("POST")&&exchange.getRequestURI().getRawQuery()==null){
                        byte[] bytes=exchange.getRequestBody().readNBytes(2049);if(bytes.length>2048)throw new IllegalArgumentException();
                        @SuppressWarnings("unchecked") Map<String,Object> body=f.json.readValue(new String(bytes,StandardCharsets.UTF_8),Map.class);
                        data=switch(path){
                            case "/admin/permissions"->{if(!body.keySet().equals(Set.of("mode")))throw new IllegalArgumentException();permissions(Objects.toString(body.get("mode")));yield Map.of("updated",true);}
                            case "/buyer/fulfill"->{if(!body.keySet().equals(Set.of("caseId")))throw new IllegalArgumentException();yield fulfill(Objects.toString(body.get("caseId")));}
                            case "/stop"->{if(!body.isEmpty())throw new IllegalArgumentException();Files.writeString(stop,"explicit local stop");yield Map.of("stopping",true);}
                            default->throw new IllegalArgumentException();
                        };
                    }else{reply(exchange,404,Map.of("code","NOT_FOUND"));return;}
                    reply(exchange,200,Map.of("fixtureOnly",true,"data",data));
                }catch(IllegalArgumentException failure){reply(exchange,400,Map.of("code","INVALID_CONTROL"));}
                catch(Exception|AssertionError failure){reply(exchange,500,Map.of("code","CONTROL_FAILED"));}
            }
        }
        private Object fulfill(String id)throws Exception{
            if(!id.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            var detail=f.send("GET","/c/aftersales/"+id,null,f.buyerToken);assertEquals(200,detail.status());
            assertEquals("USER",detail.value("supplementTarget"));assertEquals("WAITING_SUPPLEMENT",detail.value("status"));
            var result=f.send("POST","/c/aftersales/"+id+"/evidence",Map.of("expectedVersion",detail.value("version"),"supplementRequestId",detail.value("supplementRequestId"),"text","QA 隔离联调：买家已核对补证要求并提交现场补充说明。","evidenceAssetIds",List.of()),f.buyerToken);
            assertEquals(200,result.status());return result.data();
        }
        private void reply(HttpExchange exchange,int status,Object value)throws java.io.IOException{
            byte[] bytes=f.json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }
        public void close(){server.stop(0);executor.shutdownNow();}
    }
}
