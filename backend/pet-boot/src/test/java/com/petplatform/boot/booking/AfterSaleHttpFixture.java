package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.aftersale.biz.application.AfterSalePorts;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.biz.application.MerchantOrderPorts;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.refund.biz.application.*;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.user.biz.application.WechatSessionProvider;
import com.petplatform.verification.api.command.*;
import com.petplatform.verification.biz.application.*;
import io.lettuce.core.RedisClient;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Full BOOT HTTP composition. Only external WeChat, moderation, antivirus and OSS are test providers.
 * Real ordinary-refund rejection/verification establish eligibility; no positive AFS/proof rows are seeded.
 * No RefundFundingEligibilityFactsApi bean is installed. All channel calls are forbidden and counted. */
final class AfterSaleHttpFixture implements AutoCloseable {
    static final String BUYER="710100", OTHER="710101", OWNER="710300", MERCHANT="710301", STORE="710302";
    static final String ORIGIN="https://admin.example.invalid", PASSWORD="QA_AfterSale_HTTP_82!";
    private static final AtomicLong IDS=new AtomicLong(9_160_000_000_000_000L);
    final RefundApplicationAcceptanceTest.F ordinary=new RefundApplicationAcceptanceTest.F();
    final JdbcTemplate jdbc=ordinary.t.r.f.f.db.jdbc;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final JsonMapper json=JsonMapper.builder().build();
    final AtomicInteger channelCalls=new AtomicInteger();
    final Map<String,PrivateObjectStore.StoredContent> objects=new ConcurrentHashMap<>();
    final AtomicReference<Runnable> afterObjectRead=new AtomicReference<>();
    final AtomicReference<Runnable> afterProgressPublished=new AtomicReference<>();
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return ordinary.source.instant();}};
    final String redisHost=required("AUTH_REDIS_HOST"),redisPort=required("AUTH_REDIS_PORT");
    final String suffix="afs_http_"+UUID.randomUUID().toString().replace("-","");
    final String prefix="auth001_"+suffix+":",miniPrefix="auth001c_"+suffix+":";
    final Path directory;
    ConfigurableApplicationContext context;
    String baseUrl,buyerToken,otherToken,ownerToken,adminToken;

    AfterSaleHttpFixture() throws Exception {this(Map.of());}
    AfterSaleHttpFixture(Map<String,Object> overrides) throws Exception {
        directory=Files.createTempDirectory("aftersale-http-");
        try {
            var db=ordinary.t.r.f.f.db;
            db.script("26-Admin-Auth-Schema-v0.1.sql");
            db.script("31-Private-Asset-Schema-v0.1.sql");
            db.script("50-AfterSale-Workflow-Schema-v0.1.sql");
            db.script("51-AfterSale-Http-Schema-v0.1.sql");
            jdbc.update("UPDATE merchant_profile_compat SET city_code='chengdu' WHERE merchant_id=710301");
            at(Instant.parse("2030-01-01T09:00:00Z"));
            var properties=new LinkedHashMap<String,Object>();
            properties.put("server.port",0);properties.put("spring.jmx.enabled",false);properties.put("spring.flyway.enabled",false);
            for(String flag:List.of("pet.auth.c","pet.auth.admin","pet.schedule.protection","pet.order.creation","pet.order.expiry",
                    "pet.payment.foundation","pet.order.auto-confirm","pet.order.merchant","pet.refund.application",
                    "pet.verification.credential","pet.verification.completion","pet.private-assets","pet.aftersale","pet.aftersale.http"))
                properties.put(flag+".enabled",true);
            for(String flag:List.of("pet.aftersale.worker","pet.aftersale.refund","pet.order.auto-confirm.worker","pet.order.expiry.worker",
                    "pet.order.merchant.worker","pet.refund.application.worker","pet.payment.dispatch","pet.outbox"))
                properties.put(flag+".enabled",false);
            for(String auth:List.of("c","admin")){
                properties.put("pet.auth."+auth+".redis-host",redisHost);properties.put("pet.auth."+auth+".redis-port",redisPort);
                properties.put("pet.auth."+auth+".cache-prefix",auth.equals("c")?miniPrefix:prefix);
            }
            properties.put("pet.auth.admin.origin",ORIGIN);properties.put("pet.auth.admin.key-id","qa-http");
            properties.put("pet.auth.admin.mac-key-base64",key(3));properties.put("pet.auth.admin.encryption-key-base64",key(7));
            properties.put("pet.auth.admin.audit-path",directory.resolve("audit.bin"));
            properties.put("pet.auth.admin.migration-enabled",false);
            properties.put("pet.aftersale.protection-key",key(11));properties.put("pet.aftersale.type-codes","QA_QUALITY");
            properties.put("pet.aftersale.demand-codes","QA_REFUND");
            properties.put("pet.aftersale.type-labels[QA_QUALITY]","QA quality issue");
            properties.put("pet.aftersale.demand-labels[QA_REFUND]","QA requested resolution");
            properties.put("pet.refund.application.protection-key",key(13));properties.put("pet.refund.application.reason-codes","QA_REASON");
            properties.put("pet.order.merchant.protection-key",key(17));
            properties.put("pet.verification.credential.key-id","qa-http");properties.put("pet.verification.credential.encryption-key",key(19));
            properties.put("pet.verification.credential.lookup-key",key(23));
            properties.put("pet.payment.lakala.channel-time-zone","Asia/Shanghai");
            properties.put("pet.payment.dispatch.request-ip","127.0.0.1");properties.put("pet.payment.dispatch.notify-url","https://qa.invalid/refund");
            properties.put("PRIVATE_ASSET_GRANT_KEY_VERSION","qa-http-grant");properties.put("PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64",key(29));
            properties.put("PRIVATE_ASSET_REASON_KEY_VERSION","qa-http-reason");properties.put("PRIVATE_ASSET_REASON_AES_KEY_BASE64",key(31));
            properties.putAll(overrides);
            context=new SpringApplicationBuilder(PetPlatformApplication.class).initializers(c->{
                var beans=(GenericApplicationContext)c;
                beans.registerBean("qaAfterSaleDataSource",DataSource.class,()->ordinary.source);
                beans.registerBean("qaAfterSaleIds",SnowflakeIdGenerator.class,()->IDS::incrementAndGet);
                beans.registerBean("qaAfterSaleClock",Clock.class,()->clock);
                beans.registerBean("qaAfterSaleOutbox",IntegrationEventPublisher.class,()->event->{
                    ordinary.outbox.publish(event);
                    if("AfterSaleProgressChangedEvent".equals(event.eventType())){
                        var probe=afterProgressPublished.getAndSet(null);if(probe!=null)probe.run();
                    }
                });
                beans.registerBean("qaAfterSaleWechat",WechatSessionProvider.class,()->new WechatSessionProvider(){
                    public WechatIdentity exchangeIdentity(String code){if(!code.startsWith("qa-http:"))throw new ProofRejected();return new WechatIdentity("qa-afs-http-app",code.substring(8),null);}
                    public String exchangePhone(String code){if(!code.matches("phone:1[0-9]{10}"))throw new ProofRejected();return code.substring(6);}
                });
                beans.registerBean("qaAfterSaleModeration",AfterSalePorts.Moderation.class,()->value->new AfterSalePorts.Approval(sha(value),"QA_ONLY_MODERATION",true));
                beans.registerBean("qaRefundModeration",RefundApplicationPorts.Moderation.class,()->value->new RefundApplicationPorts.Approval(sha(value),"QA_ONLY_MODERATION",true));
                beans.registerBean("qaMerchantModeration",MerchantOrderPorts.Moderation.class,()->value->new MerchantOrderPorts.Approval(sha(value),"QA_ONLY_MODERATION",true));
                beans.registerBean("qaAssetScanner",PrivateAssetScanner.class,()->bytes->new PrivateAssetScanner.ScanResult(true,"QA_ONLY_SCANNER","CLEAN"));
                beans.registerBean("qaAssetObjects",PrivateObjectStore.class,()->objectStore());
                beans.registerBean("qaForbiddenRefundChannel",PaymentRefundChannel.class,()->new PaymentRefundChannel(){
                    public VerifiedResult submit(RefundRequest request){channelCalls.incrementAndGet();throw new AssertionError("HTTP slice must not submit money");}
                    public VerifiedResult query(RefundRequest request){channelCalls.incrementAndGet();throw new AssertionError("HTTP slice has no historical money to query");}
                });
            }).run(properties.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));
            baseUrl="http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port")+"/api/v1";
            context.getBean(AdminAuthService.class).bootstrap("qa-http-operator","QA HTTP operator",PASSWORD.toCharArray(),"Isolated HTTP acceptance");
            buyerToken=loginBuyer();otherToken=loginOther();ownerToken=loginOwner();adminToken=loginAdmin();
        } catch(Exception|Error failure){close();throw failure;}
    }

    PrivateObjectStore objectStore(){return new PrivateObjectStore(){
        public StoredObject putIfAbsent(String key,byte[] content,String type,String hash){
            var candidate=new StoredContent(content.clone(),type,hash);var previous=objects.putIfAbsent(key,candidate);
            var stored=previous==null?candidate:previous;
            if(!hash.equals(stored.sha256())||!Arrays.equals(content,stored.content()))throw new IllegalStateException("Immutable QA object differs");
            return new StoredObject("qa-http-version",hash,content.length,type);
        }
        public StoredContent get(String key,String version){
            if(!"qa-http-version".equals(version))throw new IllegalStateException("Wrong object version");
            var result=Objects.requireNonNull(objects.get(key));var hook=afterObjectRead.getAndSet(null);if(hook!=null)hook.run();return result;
        }
        public Optional<StoredObject> head(String key){var s=objects.get(key);return s==null?Optional.empty():Optional.of(new StoredObject("qa-http-version",s.sha256(),s.content().length,s.mediaType()));}
    };}
    String loginBuyer()throws Exception{return buyerToken=loginUser(BUYER,"13800000100");}
    String loginOther()throws Exception{return otherToken=loginUser(OTHER,"13800000101");}
    String loginOwner()throws Exception{return ownerToken=loginUser(OWNER,"13800000300");}
    String loginUser(String id,String phone)throws Exception{
        String open="user-"+id;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_identity WHERE user_id=? AND app_id='qa-afs-http-app'",Integer.class,Long.parseLong(id))==0)
            jdbc.update("INSERT INTO user_auth_identity(id,user_id,identity_type,app_id,open_id,created_at,updated_at) VALUES(?,?,'WECHAT_MINI','qa-afs-http-app',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",IDS.incrementAndGet(),Long.parseLong(id),open);
        var a=send("POST","/c/auth/attempts",Map.of("purpose","WECHAT_LOGIN"),(String)null);assertEquals(201,a.status());
        var r=send("POST","/c/auth/wechat-login",Map.of("attemptId",a.value("attemptId"),"wechatCode","qa-http:"+open,"phoneCode","phone:"+phone),
                Map.of("X-Request-Id",rid(),"X-Auth-Attempt",a.value("attemptToken")));
        assertEquals(200,r.status());assertEquals(id,r.value("userId"));return r.value("accessToken");
    }
    String loginAdmin()throws Exception{
        var a=send("POST","/admin/auth/attempts",Map.of(),Map.of("Origin",ORIGIN,"X-Request-Id",rid()));assertEquals(201,a.status());
        String cookie=a.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        var r=send("POST","/admin/auth/login",Map.of("attemptId",a.value("attemptId"),"account","qa-http-operator","password",PASSWORD),
                Map.of("Origin",ORIGIN,"Cookie",cookie,"X-Request-Id",rid(),"X-Auth-Attempt",a.value("attemptToken")));
        assertEquals(200,r.status());return adminToken=r.value("accessToken");
    }
    String rejectedOrder(){String id=ordinary.t.ready();at(clock.instant().plusSeconds(10));
        var app=context.getBean(RefundApplicationService.class);as(buyerToken);var applied=app.apply(ordinary.applyCommand(id));
        as(ownerToken);assertEquals("REJECTED",app.decide(ordinary.decision(applied,"REJECT")).applicationStatus());RequestContextHolder.resetRequestAttributes();return id;}
    String verifiedOrder(){String id=ordinary.t.ready();at(clock.instant().plusSeconds(20));
        var credentials=context.getBean(VerificationCredentialService.class);as(buyerToken);
        var view=credentials.read(id,new QueryContext("qa-http-source",OperatorType.USER,BUYER));
        var issued=credentials.issue(new VerificationCredentialApi.Issue(user(BUYER),id,view.credentialVersion(),"INITIAL"));as(ownerToken);
        var result=context.getBean(VerificationCompletionService.class).verify(new VerificationCompletionApi.Command(user(OWNER),id,STORE,issued.code(),issued.credentialVersion(),true));
        assertEquals("VERIFIED",result.resultCode());RequestContextHolder.resetRequestAttributes();return id;}
    void as(String token){AfterSaleIdentityFixture.bearer(token);}
    CommandContext user(String id){return new CommandContext(rid(),"qa-http-source",OperatorType.USER,id,"MINIAPP");}
    void at(Instant value){ordinary.source.fixed.set(value);}
    void sql(String sql){jdbc.execute(sql);}
    long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    String text(String sql){return jdbc.queryForObject(sql,String.class);}
    static String rid(){return UUID.randomUUID().toString();}
    static Map<String,String> bearer(String token){return token==null?Map.of("X-Request-Id",rid()):Map.of("X-Request-Id",rid(),"Authorization","Bearer "+token);}
    Reply send(String method,String path,Object body,String token)throws Exception{return send(method,path,body,bearer(token));}
    Reply send(String method,String path,Object body,Map<String,String> headers)throws Exception{return sendRaw(method,path,body==null?null:json.writeValueAsString(body),headers);}
    Reply sendRaw(String method,String path,String body,Map<String,String> headers)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(20));headers.forEach(request::header);
        if(body!=null)request.header("Content-Type","application/json");
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked") Map<String,Object> envelope=json.readValue(response.body(),Map.class);
        assertTrue(envelope.get("traceId") instanceof String,response.body());
        if(path.contains("aftersale")){
            assertEquals(Set.of("code","message","data","traceId"),envelope.keySet());
            assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(),envelope.get("traceId"));
        }
        if(response.statusCode()>=400){assertNull(envelope.get("data"));assertFalse(response.body().contains(PASSWORD));assertFalse(response.body().contains("SELECT "));}
        return new Reply(response.statusCode(),envelope,response.headers());
    }
    record Reply(int status,Map<String,Object> envelope,HttpHeaders headers){
        @SuppressWarnings("unchecked") Map<String,Object> data(){return (Map<String,Object>)envelope.get("data");}
        String value(String name){return Objects.toString(data().get(name),null);}
        @Override public String toString(){return "Reply[status="+status+",code="+envelope.get("code")+"]";}
    }
    static Map<String,Object> createBody(){return new LinkedHashMap<>(Map.of("typeCode","QA_QUALITY","demandCode","QA_REFUND","description","Independent HTTP report of a real service problem","evidenceAssetIds",List.of()));}
    static String key(int value){byte[] b=new byte[32];Arrays.fill(b,(byte)value);return Base64.getEncoder().encodeToString(b);}
    static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String required(String key){return Objects.requireNonNull(System.getenv(key),key+" required for real isolated acceptance");}
    public void close(){
        RequestContextHolder.resetRequestAttributes();try{if(context!=null)context.close();}finally{
            try{var client=RedisClient.create("redis://"+redisHost+":"+redisPort);try(var connection=client.connect()){
                for(String namespace:List.of(prefix,miniPrefix))for(String key:connection.sync().keys(namespace+"*"))connection.sync().del(key);
            }finally{client.shutdown();}}finally{ordinary.close();}
        }
    }
}
