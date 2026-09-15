package com.petplatform.user.biz.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Real WeChat mini-program provider over the official open APIs: jscode2session for the
 * one-time login code and getuserphonenumber for the quick phone verification code, with the
 * stable access token cached in memory only. Credentials arrive exclusively through
 * configuration (deployment environment) — never in source control, never in logs; the
 * appSecret and session_key are never logged and the request URLs are never included in
 * exceptions because they carry them.
 *
 * <p>Error mapping follows the approved auth semantics: a rejected one-time code (40029
 * invalid, 40163 already used, 41008 missing) is {@link WechatSessionProvider.ProofRejected}
 * (uniform 401); network trouble, rate limits and every other errcode — including a mis-set
 * appSecret — are {@link WechatSessionProvider.ProviderUnavailable} (503) so nothing about the
 * configuration leaks. A 40001/42001 access-token failure forces exactly one token refresh and
 * retry.
 */
public final class WechatMiniApiProvider implements WechatSessionProvider {

    /** Immutable connection settings; apiBase is overridable so tests can point at a stub. */
    public record Settings(String appId, String appSecret, String apiBase) {
        public Settings {
            if (appId == null || appId.isBlank()
                    || appSecret == null || appSecret.isBlank()
                    || apiBase == null || !apiBase.matches("https?://[A-Za-z0-9.:-]+")) {
                throw new IllegalArgumentException("Explicit WeChat appid/secret/api base required");
            }
        }

        public static Settings production(String appId, String appSecret) {
            return new Settings(appId, appSecret, "https://api.weixin.qq.com");
        }
    }

    private static final ObjectMapper CODEC = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** Codes the WeChat side definitively rejects as proof failures. */
    private static final java.util.Set<Integer> PROOF_REJECTED_CODES = java.util.Set.of(40029, 40163, 41008);
    /** Access-token is invalid or expired: refresh once and retry. */
    private static final java.util.Set<Integer> TOKEN_STALE_CODES = java.util.Set.of(40001, 42001);
    /** Refresh the cached token this long before its nominal expiry. */
    private static final Duration TOKEN_SKEW = Duration.ofSeconds(300);

    private final Settings settings;
    private final HttpClient http;
    private final Clock clock;
    private final Object tokenLock = new Object();
    private volatile String cachedToken;
    private volatile Instant cachedTokenUntil;

    public WechatMiniApiProvider(Settings settings) {
        this(settings, Clock.systemUTC());
    }

    /** Testing seam with an injectable clock; production uses {@link #WechatMiniApiProvider(Settings)}. */
    public WechatMiniApiProvider(Settings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public WechatIdentity exchangeIdentity(String wechatCode) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(settings.apiBase()
                                    + "/sns/jscode2session?appid=" + url(settings.appId())
                                    + "&secret=" + url(settings.appSecret())
                                    + "&js_code=" + url(wechatCode)
                                    + "&grant_type=authorization_code"))
                            .timeout(REQUEST_TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = parse(response);
            requireOk(body);
            // session_key is deliberately discarded: this slice never needs it and must not
            // persist or log it.
            String openId = text(body, "openid");
            if (openId == null) throw new WechatSessionProvider.ProviderUnavailable();
            return new WechatIdentity(settings.appId(), openId, text(body, "unionid"));
        } catch (WechatSessionProvider.ProofRejected rejected) {
            throw rejected;
        } catch (RuntimeException | IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new WechatSessionProvider.ProviderUnavailable();
        }
    }

    @Override
    public String exchangePhone(String phoneCode) {
        try {
            return exchangePhoneWithToken(accessToken(false), phoneCode);
        } catch (WechatSessionProvider.ProofRejected rejected) {
            throw rejected;
        } catch (StaleToken stale) {
            try {
                return exchangePhoneWithToken(accessToken(true), phoneCode);
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new WechatSessionProvider.ProviderUnavailable();
            }
        } catch (RuntimeException | IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new WechatSessionProvider.ProviderUnavailable();
        }
    }

    private String exchangePhoneWithToken(String token, String phoneCode)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(settings.apiBase()
                                + "/wxa/business/getuserphonenumber?access_token=" + url(token)))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"code\":" + json(phoneCode) + "}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = parse(response);
        if (TOKEN_STALE_CODES.contains(errcode(body))) throw new StaleToken();
        requireOk(body);
        JsonNode info = body.get("phone_info");
        String phone = info == null ? null : text(info, "purePhoneNumber");
        if (phone == null) throw new WechatSessionProvider.ProviderUnavailable();
        return phone;
    }

    /** stable_token endpoint; the token only ever lives in this instance's memory. */
    private String accessToken(boolean forceRefresh) {
        Instant now = clock.instant();
        String token = cachedToken;
        if (!forceRefresh && token != null && now.isBefore(cachedTokenUntil)) return token;
        synchronized (tokenLock) {
            now = clock.instant();
            if (!forceRefresh && cachedToken != null && now.isBefore(cachedTokenUntil)) {
                return cachedToken;
            }
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(settings.apiBase() + "/cgi-bin/stable_token"))
                                .timeout(REQUEST_TIMEOUT)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"grant_type\":\"client_credential\",\"appid\":"
                                                + json(settings.appId())
                                                + ",\"secret\":" + json(settings.appSecret()) + "}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode body = parse(response);
                requireOk(body);
                String fresh = text(body, "access_token");
                long expiresIn = body.path("expires_in").asLong(7200);
                if (fresh == null) throw new WechatSessionProvider.ProviderUnavailable();
                cachedToken = fresh;
                cachedTokenUntil = now.plusSeconds(Math.max(60, expiresIn)).minus(TOKEN_SKEW);
                return fresh;
            } catch (RuntimeException | IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new WechatSessionProvider.ProviderUnavailable();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class StaleToken extends RuntimeException {
        StaleToken() { super(null, null, false, false); }
    }

    private static JsonNode parse(HttpResponse<String> response) {
        if (response.statusCode() != 200) throw new WechatSessionProvider.ProviderUnavailable();
        try {
            return CODEC.readTree(response.body() == null ? "" : response.body());
        } catch (IOException broken) {
            throw new WechatSessionProvider.ProviderUnavailable();
        }
    }

    private static void requireOk(JsonNode body) {
        int errcode = errcode(body);
        if (errcode == 0) return;
        if (PROOF_REJECTED_CODES.contains(errcode)) throw new WechatSessionProvider.ProofRejected();
        throw new WechatSessionProvider.ProviderUnavailable();
    }

    private static int errcode(JsonNode body) {
        return body == null || body.path("errcode").isMissingNode()
                ? 0 : body.path("errcode").asInt(0);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        return node.asText();
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String json(String value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (IOException broken) {
            throw new IllegalArgumentException("Value not serializable");
        }
    }

    @Override
    public String toString() {
        // appId is public identity, but keep a fixed label so no setting ever reaches logs.
        return "WechatMiniApiProvider[configured]";
    }
}
