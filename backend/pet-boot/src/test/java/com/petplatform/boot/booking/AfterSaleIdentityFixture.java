package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.admin.biz.application.*;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.common.*;
import com.petplatform.user.biz.application.*;
import com.petplatform.user.biz.infrastructure.provider.RedisMiniAuthVolatileStore;
import io.lettuce.core.RedisClient;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Real login/session persistence and Redis; only the upstream WeChat exchange is test-controlled. */
final class AfterSaleIdentityFixture implements AutoCloseable {
    private static final char[] PASSWORD = "QA_AfterSale_Only_82!".toCharArray();
    private final String host = required("AUTH_REDIS_HOST");
    private final int port = Integer.parseInt(required("AUTH_REDIS_PORT"));
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String miniPrefix = "auth001c_afs_" + suffix + ":";
    private final String adminPrefix = "auth001_afs_" + suffix + ":";
    private final RedisMiniAuthVolatileStore miniCache;
    private final RedisAdminGrantCache adminCache;
    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator ids;
    final UserAuthService users;
    final AdminAuthService admins;
    final AdminAuthorizationService authorization;
    final Map<String, String> userTokens = new HashMap<>();
    String adminToken;
    AdminSessionView adminSession;

    AfterSaleIdentityFixture(DataSource source, SnowflakeIdGenerator ids, Clock clock) {
        this.ids = ids;
        jdbc = new JdbcTemplate(source);
        miniCache = new RedisMiniAuthVolatileStore(host, port, null, null, miniPrefix);
        adminCache = new RedisAdminGrantCache(host, port, null, null, adminPrefix);
        users = new UserAuthService(source, ids, clock, new WechatSessionProvider() {
            public WechatIdentity exchangeIdentity(String code) {
                if (!code.startsWith("qa-afs:")) throw new ProofRejected();
                return new WechatIdentity("qa-afs-app", code.substring(7), null);
            }
            public String exchangePhone(String code) {
                if (!code.matches("phone:1[0-9]{10}")) throw new ProofRejected();
                return code.substring(6);
            }
        }, miniCache, new UserAuthService.MiniAuthPolicy(300, 3600, 60, 100, 5));
        admins = new AdminAuthService(source, ids, clock, new AdminPasswordHasher(),
                AdminSecretCodec.fixed("qa-afs", key(11), key(23)), adminCache);
        authorization = new AdminAuthorizationService(source);
        admins.bootstrap("qa-afs-operator", "QA aftersale operator", PASSWORD.clone(), "Isolated AFS acceptance");
        loginAdmin();
    }

    void loginAdmin() {
        var attempt = admins.createAttempt(UUID.randomUUID().toString(), "127.0.0.1");
        var grant = admins.login(UUID.randomUUID().toString(),
                Long.parseLong(attempt.data().get("attemptId").toString()),
                attempt.data().get("attemptToken").toString(), attempt.bindingCookie(),
                "qa-afs-operator", PASSWORD.clone(), null);
        adminToken = grant.data().get("accessToken").toString();
        adminSession = admins.resolveSession(adminToken);
    }

    String loginExistingUser(String userId, String phone) {
        // Static identity provisioning belongs to the inherited booking fixture. Sessions and
        // all business outcomes are created by the real login/business APIs, never INSERTed.
        String openId = "user-" + userId;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_identity WHERE user_id=? AND app_id='qa-afs-app'",
                Integer.class, Long.parseLong(userId)) == 0)
            jdbc.update("INSERT INTO user_auth_identity(id,user_id,identity_type,app_id,open_id,created_at,updated_at)"
                    + " VALUES(?,?,'WECHAT_MINI','qa-afs-app',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    ids.nextId(), Long.parseLong(userId), openId);
        var attempt = users.createAttempt(UUID.randomUUID().toString(), "WECHAT_LOGIN", "127.0.0.1");
        var grant = users.wechatLogin(UUID.randomUUID().toString(), attempt.get("attemptId").toString(),
                attempt.get("attemptToken").toString(), "qa-afs:" + openId, "phone:" + phone);
        assertEquals(userId, grant.get("userId"));
        String token = grant.get("accessToken").toString();
        assertEquals(userId, users.resolveSession(token).userId());
        userTokens.put(userId, token);
        return token;
    }

    void asUser(String userId) { bearer(Objects.requireNonNull(userTokens.get(userId))); }
    void asAdmin() { bearer(adminToken); }
    CommandContext adminContext() {
        return new CommandContext(UUID.randomUUID().toString(), "afs-qa", OperatorType.PLATFORM_OPERATOR,
                adminSession.principal().operatorId(), "ADMIN_WEB");
    }
    void revokeAdmin() { admins.logout(UUID.randomUUID().toString(), adminToken); }
    void revokeUser(String userId) { users.logout(UUID.randomUUID().toString(), userTokens.get(userId)); }
    void revokeAdminWritesKeepRead() {
        // Deliberate revocation fault, not a positive authorization/business-source fixture.
        // Real ADMIN authorization must reread these owner rows even for a successful replay.
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(ignored -> {
            jdbc.queryForObject("SELECT revision FROM admin_authz_revision WHERE id=1 FOR UPDATE", Long.class);
            long actor = Long.parseLong(adminSession.principal().operatorId());
            jdbc.update("DELETE FROM admin_account_role WHERE account_id=?", actor);
            jdbc.update("DELETE FROM admin_extra_grant WHERE account_id=?", actor);
            jdbc.update("INSERT INTO admin_extra_grant(account_id,action_code,granted_by,granted_at) VALUES(?,'aftersale.read',?,UTC_TIMESTAMP(3))", actor, actor);
            jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
        });
    }
    static void bearer(String token) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    static byte[] key(int fill) { byte[] key = new byte[32]; Arrays.fill(key, (byte) fill); return key; }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " for isolated AFS acceptance");
        return value;
    }
    public void close() {
        RequestContextHolder.resetRequestAttributes();
        try { miniCache.close(); } finally { adminCache.close(); }
        var client = RedisClient.create("redis://" + host + ":" + port);
        try (var connection = client.connect()) {
            for (String prefix : List.of(miniPrefix, adminPrefix)) {
                for (String key : connection.sync().keys(prefix + "*")) connection.sync().del(key);
            }
        } finally { client.shutdown(); }
    }
}
