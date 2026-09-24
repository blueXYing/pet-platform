package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/** Raw HTTP and real MySQL/Redis smoke for staff routing, strict input and historical receipts. */
class MerchantStaffHttpTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;

    record Reply(int status, Map<String,Object> body) {
        @SuppressWarnings("unchecked") Map<String,Object> data() { return (Map<String,Object>) body.get("data"); }
    }
    @Test void fiveRoutesAreWiredWithBearerAndDisableIsBlocked() throws Exception {
        try (var db = new CAuthHttpTest.HttpFixture()) {
            Path root = CAuthHttpTest.HttpFixture.root();
            try (var connection = db.source.getConnection()) {
                for (String sql : List.of("28-Merchant-Agreement-Schema-v0.1.sql",
                        "29-Merchant-Application-Schema-v0.1.sql",
                        "35-Merchant-Staff-Audit-Schema-v0.1.sql"))
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                            root.resolve("docs/03-database/" + sql)), StandardCharsets.UTF_8));
            }
            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(PetPlatformApplication.class)
                    .initializers(ctx -> {
                        var beans = (GenericApplicationContext) ctx;
                        beans.registerBean("staffDataSource", javax.sql.DataSource.class, () -> db.source);
                        beans.registerBean("staffIds", SnowflakeIdGenerator.class, () -> db.ids);
                        beans.registerBean("staffWechat", WechatSessionProvider.class,
                                CAuthHttpTest.FixedWechatProvider::new);
                        // Approval projection is an explicit local HTTP test seam; independent QA
                        // exercises the entire real SQL29 review chain before staff writes.
                        beans.registerBean("staffApplicationFacts", ApplicationReviewFactsReader.class,
                                () -> merchantId -> new ApplicationReviewFactsReader.Facts("APPROVED"));
                        beans.registerBean("staffProtectedCanonical", ApplicationValidationPorts.ProtectedValuePort.class,
                                () -> new AesGcmProtectedValueProvider("staff-http-v1", key((byte) 1), key((byte) 2)));
                    }).run("--server.port=0", "--spring.flyway.enabled=false",
                            "--pet.auth.c.enabled=true", "--pet.auth.c.redis-host=" + db.redisHost,
                            "--pet.auth.c.redis-port=" + db.redisPort,
                            "--pet.auth.c.cache-prefix=" + db.prefix,
                            "--pet.merchant.staff.enabled=true")) {
                base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
                String attemptId, attemptToken;
                Reply attempt = send("POST", "/api/v1/c/auth/attempts",
                        "{\"purpose\":\"WECHAT_LOGIN\"}", Map.of("X-Request-Id", rid()));
                assertEquals(201, attempt.status());
                attemptId = String.valueOf(attempt.data().get("attemptId"));
                attemptToken = String.valueOf(attempt.data().get("attemptToken"));
                Reply login = send("POST", "/api/v1/c/auth/wechat-login",
                        json.writeValueAsString(Map.of("attemptId", attemptId, "wechatCode", "ok:staff-owner",
                                "phoneCode", "phone:13800008888")),
                        Map.of("X-Request-Id", rid(), "X-Auth-Attempt", attemptToken));
                assertEquals(200, login.status());
                String token = String.valueOf(login.data().get("accessToken"));
                long ownerId = Long.parseLong(String.valueOf(login.data().get("userId")));
                long merchant = 9_007_199_254_740_993L, store = merchant + 1;
                seed(db, merchant, store, ownerId);

                String path = "/api/v1/merchant/staff";
                assertEquals(401, send("GET", path + "?merchantId=" + merchant + "&storeId=" + store,
                        null, Map.of()).status());
                String create = json.writeValueAsString(Map.of("merchantId", Long.toString(merchant),
                        "storeId", Long.toString(store), "staffName", "张三", "phone", "13800138000",
                        "employmentStatus", "ACTIVE", "serviceEnabled", false));
                String key = rid();
                Reply first = send("POST", path, create, bearer(token, key));
                assertEquals(201, first.status(), first.body().toString());
                assertEquals("138****8000", first.data().get("phoneMasked"));
                String staffId = String.valueOf(first.data().get("staffId"));
                assertEquals(200, send("POST", path, create, bearer(token, key)).status());
                assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_staff_audit", Integer.class));
                assertEquals(200, send("GET", path + "/" + staffId + "?merchantId=" + merchant
                        + "&storeId=" + store, null, bearer(token, rid())).status());
                assertEquals(200, send("GET", path + "?merchantId=" + merchant + "&storeId=" + store,
                        null, bearer(token, rid())).status());
                String update = "{\"merchantId\":\"" + merchant + "\",\"storeId\":\"" + store
                        + "\",\"staffName\":\"李四\",\"expectedVersion\":\"0\"}";
                Reply changed = send("PUT", path + "/" + staffId, update, bearer(token, rid()));
                assertEquals(200, changed.status(), changed.body().toString());
                assertNull(changed.data().get("phoneMasked"));
                String enable = "{\"merchantId\":\"" + merchant + "\",\"storeId\":\"" + store
                        + "\",\"expectedVersion\":\"1\"}";
                assertEquals(200, send("POST", path + "/" + staffId + "/enable", enable,
                        bearer(token, rid())).status());
                assertEquals(403, send("POST", path + "/" + staffId + "/disable", enable,
                        bearer(token, rid())).status());
                assertEquals(400, send("POST", path, create.replace("\"staffName\"", "\"unknown\""),
                        bearer(token, rid())).status());
            }
        }
    }

    private static void seed(CAuthHttpTest.HttpFixture db, long merchant, long store, long owner) {
        db.jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                merchant, owner, "HTTP员工商家");
        db.jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                store, merchant, "HTTP门店", "地址");
        db.jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(?,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                merchant + 2, owner, merchant);
        String content = "HTTP员工协议";
        String hash = sha(content);
        db.jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                merchant + 3, "staff-http", content, hash, owner);
        db.jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                merchant + 4, merchant, merchant + 3, owner, hash);
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static byte[] key(byte value) { byte[] bytes = new byte[32]; Arrays.fill(bytes, value); return bytes; }
    private Reply send(String method, String path, String body, Map<String,String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked") Map<String,Object> value = json.readValue(response.body(), Map.class);
        return new Reply(response.statusCode(), value);
    }
    private static Map<String,String> bearer(String token, String requestId) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
    }
    private static String rid() { return UUID.randomUUID().toString(); }
}
