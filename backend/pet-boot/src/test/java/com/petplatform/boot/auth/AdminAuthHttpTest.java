package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.*;
import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/** Actual loopback HTTP + production Boot/controller/service + real MySQL/Redis/Hutool. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAuthHttpTest {
    private static final String ORIGIN="https://admin.example.invalid",PASSWORD="Example_ONLY_92!";
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client=HttpClient.newHttpClient();
    HttpFixture fixture;ConfigurableApplicationContext context;String base;
    @BeforeAll void start()throws Exception{
        fixture=new HttpFixture();
        Map<String,Object> props=new HashMap<>();
        props.put("server.port",0);props.put("spring.flyway.enabled",false);props.put("spring.main.banner-mode","off");
        props.put("pet.auth.admin.enabled",true);props.put("pet.auth.admin.migration-enabled",false);props.put("pet.auth.admin.origin",ORIGIN);
        props.put("pet.auth.admin.redis-host",fixture.redisHost);props.put("pet.auth.admin.redis-port",fixture.redisPort);props.put("pet.auth.admin.cache-prefix",fixture.prefix);
        props.put("pet.auth.admin.key-id","qa-key");props.put("pet.auth.admin.mac-key-base64",Base64.getEncoder().encodeToString(fixture.mac));
        props.put("pet.auth.admin.encryption-key-base64",Base64.getEncoder().encodeToString(fixture.aes));props.put("pet.auth.admin.audit-path",fixture.directory.resolve("audit.bin").toString());
        props.put("spring.jmx.enabled",false);
        try {
            context=new SpringApplicationBuilder(PetPlatformApplication.class).properties(props)
                    .initializers(c->{var beans=(GenericApplicationContext)c;beans.registerBean("qaAuthDataSource",DataSource.class,()->fixture.source);beans.registerBean("qaAuthHutool",SnowflakeIdGenerator.class,()->fixture.ids);}).run();
            base="http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port")+"/api/v1/admin/auth";
            context.getBean(AdminAuthService.class).bootstrap("qa-owner","QA Owner",PASSWORD.toCharArray(),"HTTP isolated initialization");
        } catch(Exception failure){if(context!=null)context.close();fixture.close();throw failure;}
    }
    @AfterAll void stop()throws Exception{try{if(context!=null)context.close();}finally{if(fixture!=null)fixture.close();}}
    record Attempt(String id,String token,String cookie){@Override public String toString(){return "Attempt[REDACTED]";}}
    record Reply(int status,Map<String,Object> envelope,HttpHeaders headers){
        @SuppressWarnings("unchecked") Map<String,Object> data(){return (Map<String,Object>)envelope.get("data");}
        @Override public String toString(){return "Reply[status="+status+",data=REDACTED]";}
    }
    private Reply send(String method,String path,Object body,Map<String,String> headers)throws Exception{
        return sendRaw(method,path,body==null?null:json.writeValueAsString(body),headers);
    }
    @SuppressWarnings("unchecked") private Reply sendRaw(String method,String path,String body,Map<String,String> headers)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));headers.forEach(builder::header);
        if(body!=null)builder.header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        Map<String,Object> envelope=json.readValue(response.body(),Map.class);
        assertTrue(envelope.get("traceId") instanceof String);assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        if(response.statusCode()>=400){assertNull(envelope.get("data"));assertFalse(response.body().contains(PASSWORD));}
        return new Reply(response.statusCode(),envelope,response.headers());
    }
    private static String key(){return UUID.randomUUID().toString();}
    private Map<String,String> attemptHeaders(Attempt a){return Map.of("X-Auth-Attempt",a.token,"Cookie",a.cookie,"Origin",ORIGIN,"X-Request-Id",key());}
    private Attempt attempt()throws Exception{
        var response=send("POST","/attempts",Map.of(),Map.of("Origin",ORIGIN,"X-Request-Id",key()));assertEquals(201,response.status);
        String cookie=response.headers.firstValue("Set-Cookie").orElseThrow();assertTrue(cookie.contains("Secure"));assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));assertTrue(cookie.contains("Path=/"));
        return new Attempt(response.data().get("attemptId").toString(),response.data().get("attemptToken").toString(),cookie.split(";",2)[0]);
    }
    private Reply login(Attempt a,Map<String,String> headers)throws Exception{return send("POST","/login",Map.of("attemptId",a.id,"account","qa-owner","password",PASSWORD),headers);}
    private String loginToken()throws Exception{var a=attempt();var r=login(a,attemptHeaders(a));assertEquals(200,r.status);return r.data().get("accessToken").toString();}
    private static Map<String,String> bearer(String token){return Map.of("Authorization","Bearer "+token,"X-Request-Id",key());}

    @Test void allTenWebOperationsRunThroughRealHttpWithIndependentCaptchaProof()throws Exception{
        var a=attempt();assertEquals(200,send("GET","/attempts/"+a.id+"/requirements",null,attemptHeaders(a)).status);
        var challenge=send("POST","/captcha/challenges",Map.of("attemptId",a.id),attemptHeaders(a));assertEquals(200,challenge.status);
        String cid=challenge.data().get("captchaId").toString();assertTrue(challenge.data().get("imageDataUrl").toString().startsWith("data:image/png;base64,"));
        var codec=AdminSecretCodec.fixed("qa-key",fixture.mac,fixture.aes);
        fixture.jdbc.update("UPDATE admin_captcha SET answer_mac=? WHERE id=?",codec.mac("qa-key","CAPTCHA","ABC234"),Long.parseLong(cid));
        var proof=send("POST","/captcha/verify",Map.of("attemptId",a.id,"captchaId",cid,"answer","ABC234"),attemptHeaders(a));assertEquals(200,proof.status);
        String rid=key();var headers=new HashMap<>(attemptHeaders(a));headers.put("X-Request-Id",rid);
        var granted=send("POST","/login",Map.of("attemptId",a.id,"account","qa-owner","password",PASSWORD,"captchaProof",proof.data().get("captchaProof")),headers);
        assertEquals(200,granted.status);assertEquals("ADMIN_WEB",granted.data().get("audience"));assertFalse(granted.data().containsKey("refreshToken"));assertFalse(granted.data().containsKey("mfaVerified"));
        String token=granted.data().get("accessToken").toString();
        assertEquals(200,send("GET","/attempts/"+a.id+"/result?requestId="+rid,null,attemptHeaders(a)).status);
        assertEquals(200,send("GET","/session",null,bearer(token)).status);
        var permission=send("GET","/permissions",null,bearer(token));assertEquals(200,permission.status);assertEquals(List.of("merchant.application.decide", "merchant.application.read", "merchant.identity.reveal", "service.force.offline", "service.review.decide", "service.review.read"),permission.data().get("actionCodes"));
        var activityHeaders=bearer(token);var activity=send("POST","/activity",Map.of(),activityHeaders);assertEquals(200,activity.status);assertEquals(activity.data(),send("POST","/activity",Map.of(),activityHeaders).data());
        var logoutHeaders=bearer(token);assertEquals(200,send("POST","/logout",Map.of(),logoutHeaders).status);assertEquals(200,send("POST","/logout",Map.of(),logoutHeaders).status);
        assertEquals(401,send("GET","/session",null,bearer(token)).status);
    }
    @Test void attemptSecretCookieAndOriginAreAllRequired()throws Exception{
        var a=attempt();var full=attemptHeaders(a);
        for(String missing:List.of("X-Auth-Attempt","Cookie","Origin")){
            var headers=new HashMap<>(full);headers.remove(missing);var reply=login(a,headers);
            assertEquals(missing.equals("Origin")?403:401,reply.status,missing);
        }
        var cross=new HashMap<>(full);cross.put("Origin","https://attacker.example.invalid");assertEquals(403,login(a,cross).status);
        var other=attempt();var mixed=new HashMap<>(full);mixed.put("Cookie",other.cookie);assertEquals(401,login(a,mixed).status);
        assertEquals(401,send("GET","/permissions",null,bearer(a.token)).status);
    }
    @Test void malformedUnknownDuplicateAndNullFieldsAreRejectedWithoutSideEffects()throws Exception{
        var a=attempt();var headers=attemptHeaders(a);
        assertEquals(400,send("POST","/login",Map.of("attemptId",Long.parseLong(a.id),"account","qa-owner","password",PASSWORD),headers).status);
        assertEquals(400,send("POST","/login",Map.of("attemptId",a.id,"account","qa-owner","password",PASSWORD,"mfaVerified",true),headers).status);
        assertEquals(400,sendRaw("POST","/login","{\"attemptId\":\""+a.id+"\",\"account\":\"qa-owner\",\"password\":null}",headers).status);
        assertEquals(400,sendRaw("POST","/login","{\"attemptId\":\""+a.id+"\",\"account\":\"qa-owner\",\"account\":\"other\",\"password\":\""+PASSWORD+"\"}",headers).status);
        var badKey=new HashMap<>(headers);badKey.put("X-Request-Id","not-a-uuid");assertEquals(400,login(a,badKey).status);
    }
    @Test void repeatedLoginReturnsSameGrantAndNewLoginInvalidatesPreviousHttpSession()throws Exception{
        var a=attempt();var headers=attemptHeaders(a);var first=login(a,headers);assertEquals(200,first.status);assertEquals(first.data(),login(a,headers).data());
        String old=first.data().get("accessToken").toString(),current=loginToken();assertNotEquals(old,current);
        assertEquals(401,send("GET","/session",null,bearer(old)).status);assertEquals(200,send("GET","/session",null,bearer(current)).status);
        assertEquals(403,send("GET","/mfa/config",null,bearer(current)).status);
    }
    @Test void wrongAndAbsentAccountsHaveIdenticalHttpCredentialErrors()throws Exception{
        Map<String,Object> first=null;
        for(String account:List.of("qa-owner","missing-owner")){
            var a=attempt();var response=send("POST","/login",Map.of("attemptId",a.id,"account",account,"password","Wrong_ONLY_92!"),attemptHeaders(a));assertEquals(401,response.status);
            var body=new HashMap<>(response.envelope);body.remove("traceId");if(first==null)first=body;else assertEquals(first,body);
        }
    }

    /** Local copy avoids exporting a domain test helper through a test-jar dependency. */
    static final class HttpFixture implements AutoCloseable {
        final String name="auth001_http_"+UUID.randomUUID().toString().replace("-",""),prefix=name+":";
        final String redisHost=required("AUTH_REDIS_HOST");final int redisPort=Integer.parseInt(required("AUTH_REDIS_PORT"));
        final byte[] mac=bytes(3),aes=bytes(7);final Path directory;
        final DataSource source;final JdbcTemplate jdbc,admin;HutoolSnowflakeIdProvider ids;boolean created;
        HttpFixture()throws Exception{
            String server=required("AUTH_MYSQL_URL");if(!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))throw new IllegalArgumentException("Dedicated local MySQL required");
            if(!Set.of("127.0.0.1","localhost").contains(redisHost))throw new IllegalArgumentException("Dedicated local Redis required");
            directory=Files.createTempDirectory("auth001-http-");source=source(server+name);jdbc=new JdbcTemplate(source);admin=new JdbcTemplate(source(server));
            admin.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4");created=true;
            try {
                if(!admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."))throw new IllegalStateException("Real MySQL 8 required");
                Path root=root();try(var c=source.getConnection();var files=Files.list(root.resolve("docs/03-database"))){Path idSchema=files.filter(p->p.getFileName().toString().startsWith("25-")&&p.toString().endsWith(".sql")).findFirst().orElseThrow();ScriptUtils.executeSqlScript(c,new FileSystemResource(idSchema));ScriptUtils.executeSqlScript(c,new FileSystemResource(root.resolve("docs/03-database/26-Admin-Auth-Schema-v0.1.sql")));}
                String evidence="qa-auth-http-virgin:"+name;jdbc.update("INSERT INTO"
                + " snowflake_worker_state(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
                + " VALUES(18,?,TRUE,?,NOW(3),NOW(3))",SnowflakeProviderSettings.FORMAT_IDENTITY,evidence);
                ids=new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source),new SnowflakeProviderSettings(18),old->{if(!created||old.nodeId()!=18||old.incarnation()!=null||old.fence()!=0||old.reservedThrough()!=-1||!evidence.equals(old.initializationRef()))throw new IllegalStateException("Not this fixture's virgin node");});
                long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(true){try{ids.nextId();break;}catch(IllegalStateException e){if(!e.getMessage().contains("WARMING")||System.nanoTime()>=deadline)throw e;Thread.sleep(20);}}
            }catch(Exception e){close();throw e;}
        }
        static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalStateException(key+" required; cannot skip real integration");return v;}
        static byte[] bytes(int n){byte[] b=new byte[32];Arrays.fill(b,(byte)n);return b;}
        static Path root(){Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.isDirectory(p.resolve("docs/03-database")))p=p.getParent();return Objects.requireNonNull(p);}
        static DataSource source(String url){return new DriverManagerDataSource(url+"?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&connectTimeout=1000&socketTimeout=5000",System.getenv().getOrDefault("AUTH_MYSQL_USER","root"),System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD",""));}
        public void close()throws Exception{
            if(!created)return;
            try{var client=RedisClient.create("redis://"+redisHost+":"+redisPort);try(var connection=client.connect()){for(String key:connection.sync().keys(prefix+"*"))connection.sync().del(key);}finally{client.shutdown();}}
            finally{if(ids!=null)ids.close();admin.execute("DROP DATABASE `"+name+"`");created=false;try(var files=Files.list(directory)){for(Path file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(directory);}
        }
    }
}
