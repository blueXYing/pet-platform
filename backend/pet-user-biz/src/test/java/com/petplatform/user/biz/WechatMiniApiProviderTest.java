package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.user.biz.application.WechatSessionProvider;
import com.petplatform.user.biz.infrastructure.provider.WechatMiniApiProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Protocol mapping of the real provider against a loopback stub imitating the official
 * endpoints. No real WeChat credentials or network are involved anywhere; the same mapping is
 * what production hits on api.weixin.qq.com.
 */
class WechatMiniApiProviderTest {

    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> phoneCallsByToken = new ConcurrentHashMap<>();
    private final AtomicLong tokenCounter = new AtomicLong();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Mutable clock so token-expiry refresh can be exercised without sleeping. */
    private static final class TickingClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-15T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration step) { now = now.plus(step); }
    }

    private WechatMiniApiProvider start(TickingClock clock, boolean staleFirstToken) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/stable_token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"access_token\":\"T"
                    + tokenCounter.incrementAndGet() + "\",\"expires_in\":7200}");
        });
        server.createContext("/sns/jscode2session", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query.contains("appid=my-app") && query.contains("grant_type=authorization_code")) {
                if (query.contains("js_code=good")) {
                    respond(exchange, 200,
                            "{\"openid\":\"open-42\",\"unionid\":\"union-42\",\"session_key\":\"sk\"}");
                } else if (query.contains("js_code=used")) {
                    respond(exchange, 200, "{\"errcode\":40163,\"errmsg\":\"code been used\"}");
                } else {
                    respond(exchange, 200, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
                }
            } else {
                respond(exchange, 200, "{\"errcode\":-1,\"errmsg\":\"system busy\"}");
            }
        });
        server.createContext("/wxa/business/getuserphonenumber", exchange -> {
            String token = queryValue(exchange, "access_token");
            phoneCallsByToken.merge(token, 1, Integer::sum);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (staleFirstToken && "T1".equals(token)) {
                respond(exchange, 200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
            } else if (body.contains("\"phone:good\"")) {
                respond(exchange, 200,
                        "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800001111\","
                                + "\"countryCode\":\"86\"}}");
            } else {
                respond(exchange, 200, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
            }
        });
        server.start();
        return new WechatMiniApiProvider(
                new WechatMiniApiProvider.Settings("my-app", "my-secret",
                        "http://127.0.0.1:" + server.getAddress().getPort()),
                clock);
    }

    private static String queryValue(HttpExchange exchange, String key) {
        for (String pair : exchange.getRequestURI().getQuery().split("&")) {
            if (pair.startsWith(key + "=")) return pair.substring(key.length() + 1);
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test void identityExchangeMapsOpenidAndUnionidAndDropsSessionKey() throws Exception {
        WechatMiniApiProvider provider = start(new TickingClock(), false);
        WechatSessionProvider.WechatIdentity identity = provider.exchangeIdentity("good");
        assertEquals("open-42", identity.openId());
        assertEquals("union-42", identity.unionId());
        assertEquals("my-app", identity.appId());
    }

    @Test void rejectedAndUsedCodesAreProofRejections() throws Exception {
        WechatMiniApiProvider provider = start(new TickingClock(), false);
        assertThrows(WechatSessionProvider.ProofRejected.class,
                () -> provider.exchangeIdentity("bad"));
        assertThrows(WechatSessionProvider.ProofRejected.class,
                () -> provider.exchangeIdentity("used"));
        assertThrows(WechatSessionProvider.ProofRejected.class,
                () -> provider.exchangePhone("phone:bad"));
    }

    @Test void tokenIsCachedUntilNearExpiryThenRefreshed() throws Exception {
        TickingClock clock = new TickingClock();
        WechatMiniApiProvider provider = start(clock, false);
        assertEquals("13800001111", provider.exchangePhone("phone:good"));
        assertEquals("13800001111", provider.exchangePhone("phone:good"));
        assertEquals(1, tokenRequests.size(), "second call reuses the cached stable token");
        clock.advance(Duration.ofHours(2));
        assertEquals("13800001111", provider.exchangePhone("phone:good"));
        assertEquals(2, tokenRequests.size(), "expired token forces exactly one refresh");
    }

    @Test void staleAccessTokenForcesOneRefreshAndRetry() throws Exception {
        WechatMiniApiProvider provider = start(new TickingClock(), true);
        assertEquals("13800001111", provider.exchangePhone("phone:good"));
        assertEquals(2, tokenRequests.size(), "40001 refreshed the token once");
        assertEquals(1, phoneCallsByToken.getOrDefault("T1", 0));
        assertEquals(1, phoneCallsByToken.getOrDefault("T2", 0));
    }

    @Test void networkFailureIsProviderUnavailableNotProofFailure() throws Exception {
        WechatMiniApiProvider provider = start(new TickingClock(), false);
        server.stop(0);
        server = null; // the base URL now points at a dead port: connection refused
        assertThrows(WechatSessionProvider.ProviderUnavailable.class,
                () -> provider.exchangeIdentity("good"));
    }

    @Test void settingsRejectBlankCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> new WechatMiniApiProvider.Settings("", "secret", "https://api.weixin.qq.com"));
        assertThrows(IllegalArgumentException.class,
                () -> new WechatMiniApiProvider.Settings("app", "  ", "https://api.weixin.qq.com"));
    }
}
