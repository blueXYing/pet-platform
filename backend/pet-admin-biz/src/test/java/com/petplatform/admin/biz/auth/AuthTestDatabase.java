package com.petplatform.admin.biz.auth;

import com.petplatform.admin.biz.application.*;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.id.core.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real isolated services only. Missing environment fails; no H2 or assumption fallback. */
final class AuthTestDatabase implements AutoCloseable {
    static final String PASSWORD = "Example_ONLY_92!";
    final String name = "auth001_test_" + UUID.randomUUID().toString().replace("-", "");
    final String prefix = name + ":";
    final DataSource source;
    final JdbcTemplate jdbc, admin;
    final AdminSecretCodec codec = AdminSecretCodec.fixed("qa-key", bytes(3), bytes(7));
    final AdminPasswordHasher hasher = new AdminPasswordHasher();
    HutoolSnowflakeIdProvider ids;
    RedisAdminGrantCache cache;
    RedisClient redisClient;
    StatefulRedisConnection<String,String> redis;
    private boolean created;

    AuthTestDatabase() throws Exception {
        String server = required("AUTH_MYSQL_URL");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
            throw new IllegalArgumentException("AUTH_MYSQL_URL must name a dedicated local server without database or parameters");
        String host = required("AUTH_REDIS_HOST");
        if (!Set.of("127.0.0.1", "localhost").contains(host)) throw new IllegalArgumentException("Local dedicated AUTH Redis required");
        int port = Integer.parseInt(required("AUTH_REDIS_PORT"));
        source = dataSource(server + name); jdbc = new JdbcTemplate(source); admin = new JdbcTemplate(dataSource(server));
        admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4"); created = true;
        try {
            if (!admin.queryForObject("SELECT VERSION()", String.class).startsWith("8.")) throw new IllegalStateException("Real MySQL 8 required");
            Path root = root();
            try (Connection c = source.getConnection(); var files = Files.list(root.resolve("docs/03-database"))) {
                Path idSchema = files.filter(p -> p.getFileName().toString().startsWith("25-") && p.toString().endsWith(".sql")).findFirst().orElseThrow();
                ScriptUtils.executeSqlScript(c, new FileSystemResource(idSchema));
                ScriptUtils.executeSqlScript(c, new FileSystemResource(root.resolve("docs/03-database/26-Admin-Auth-Schema-v0.1.sql")));
            }
            String evidence = "qa-auth-virgin:" + name;
            jdbc.update("INSERT INTO"
              + " snowflake_worker_state(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
              + " VALUES(17,?,TRUE,?,NOW(3),NOW(3))", SnowflakeProviderSettings.FORMAT_IDENTITY, evidence);
            ids = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source), new SnowflakeProviderSettings(17), old -> {
                if (!created || old.nodeId()!=17 || old.incarnation()!=null || old.fence()!=0 || old.reservedThrough()!=-1 || !evidence.equals(old.initializationRef()))
                    throw new IllegalStateException("Not this fixture's specifically initialized virgin row");
            });
            warm(ids);
            cache = new RedisAdminGrantCache(host, port, null, null, prefix);
            redisClient = RedisClient.create("redis://" + host + ":" + port);
            redis = redisClient.connect();
            if (!"".equals(redis.sync().configGet("save").get("save")) || !"no".equals(redis.sync().configGet("appendonly").get("appendonly")))
                throw new IllegalStateException("AUTH Redis must disable RDB and AOF");
        } catch (Exception e) { try { close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); } throw e; }
    }
    static String required(String name) { String v=System.getenv(name); if(v==null||v.isBlank())throw new IllegalStateException(name+" is required; integration test cannot be skipped"); return v; }
    static byte[] bytes(int value) { byte[] b=new byte[32]; Arrays.fill(b,(byte)value); return b; }
    static String request() { return UUID.randomUUID().toString(); }
    static Path root() { Path p=Path.of("").toAbsolutePath(); while(p!=null&&!Files.isDirectory(p.resolve("docs/03-database")))p=p.getParent(); return Objects.requireNonNull(p); }
    static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url+"?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&connectTimeout=1000&socketTimeout=5000",System.getenv().getOrDefault("AUTH_MYSQL_USER","root"),System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD",""));
    }
    static void warm(HutoolSnowflakeIdProvider ids) throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(true) { try { ids.nextId(); return; } catch(IllegalStateException e) { if(!e.getMessage().contains("WARMING")||System.nanoTime()>=deadline)throw e; Thread.sleep(20); } }
    }
    AdminAuthService service() { return service(source, codec, cache);
  }

  AdminAuthorizationService authorization() {
    return new AdminAuthorizationService(source); }
    AdminAuthService service(DataSource ds, AdminSecretCodec keys, AdminGrantCache grants) { return new AdminAuthService(ds,ids,Clock.systemUTC(),hasher,keys,grants); }
    long bootstrap(AdminAuthService service) { return service.bootstrap("qa-owner","QA Owner",PASSWORD.toCharArray(),"Isolated test initialization"); }
    record Attempt(long id,String token,String cookie) { @Override public String toString(){return "Attempt[REDACTED]";} }
    Attempt attempt(AdminAuthService service) { return attempt(service,"127.0.0.1"); }
    Attempt attempt(AdminAuthService service,String ip) { var r=service.createAttempt(request(),ip);return new Attempt(Long.parseLong(r.data().get("attemptId").toString()),r.data().get("attemptToken").toString(),r.bindingCookie()); }
    AdminSecretResult login(AdminAuthService service,Attempt a,String rid) {return service.login(rid,a.id,a.token,a.cookie,"qa-owner",PASSWORD.toCharArray(),null);}
    String loginToken(AdminAuthService service) {return login(service,attempt(service),request()).data().get("accessToken").toString();}
    long count(String table) { if(!table.matches("admin_[a-z_]+"))throw new IllegalArgumentException();return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    void knownCaptcha(long id,String answer) {jdbc.update("UPDATE admin_captcha SET answer_mac=?,mac_key_id='qa-key' WHERE id=?",codec.mac("qa-key","CAPTCHA",answer),id);}
    static AdminAuthFailure failure(int status,Runnable action) {var e=org.junit.jupiter.api.Assertions.assertThrows(AdminAuthFailure.class,action::run);org.junit.jupiter.api.Assertions.assertEquals(status,e.status());return e;}

    /** Arm after setup. Can lose a real commit ACK or reject writes to a selected table. */
    static final class FaultSource extends AbstractDataSource {
        final DataSource delegate;
        volatile String failSql; volatile boolean offline;
        volatile String loseCommitAfterSql;
        final AtomicBoolean ackLost=new AtomicBoolean();
        FaultSource(DataSource delegate){this.delegate=delegate;}
        public Connection getConnection() throws SQLException {
            if(offline)throw new SQLException("QA dependency outage");
            Connection c=delegate.getConnection();
            AtomicBoolean touched=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,m,args)->{
                if(m.getName().equals("prepareStatement")&&failSql!=null&&args[0].toString().contains(failSql))throw new SQLException("QA rejected statement");
                if(m.getName().equals("prepareStatement")&&loseCommitAfterSql!=null&&args[0].toString().contains(loseCommitAfterSql))touched.set(true);
                try {Object result=m.invoke(c,args);if(m.getName().equals("commit")&&touched.get()&&ackLost.compareAndSet(false,true))throw new SQLException("QA lost commit acknowledgement");return result;}
                catch(InvocationTargetException e){throw e.getCause();}
            });
        }
        public Connection getConnection(String u,String p)throws SQLException{return getConnection();}
    }
    @Override public void close() {
        try { if(redis!=null){for(String key:redis.sync().keys(prefix+"*"))redis.sync().del(key);redis.close();} }
        finally {try {if(cache!=null)cache.close();if(redisClient!=null)redisClient.shutdown();if(ids!=null)ids.close();} finally {if(created){admin.execute("DROP DATABASE `"+name+"`");created=false;}}}
    }
}
