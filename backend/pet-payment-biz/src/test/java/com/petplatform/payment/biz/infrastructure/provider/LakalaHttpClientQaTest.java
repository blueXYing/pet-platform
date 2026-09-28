package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Reason;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.math.BigDecimal;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Offline transport contract checks. Keys exist only in this test process. */
class LakalaHttpClientQaTest {
    private static final String APP = "local-test-app";
    private static final String MERCHANT_SERIAL = "local-merchant-serial";
    private static final String PLATFORM_SERIAL = "local-platform-serial";
    private static final String SUB_APP = "wx-local-sub-app";
    private static final String OPEN_ID = "private-local-openid";
    private static final String PAY_SIGN = Base64.getEncoder().encodeToString(new byte[256]);
    private static final RequestNonce NONCE = new RequestNonce("1727499000", "AbCd12345678");
    private static KeyPair merchant;
    private static KeyPair platform;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchant = generator.generateKeyPair();
        platform = generator.generateKeyPair();
    }

    @Test
    void preorderSignsExactlyPublishedBytesAndBindsWechatParameters() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        LakalaHttpClient client = client(request -> {
            sent.set(request);
            return signed(200, preorderResponse(SUB_APP, "prepay_id=PREPAY123"));
        });

        var result = client.preorder(preorder(), NONCE);

        assertEquals(SUB_APP, result.appId());
        assertEquals("PREPAY123", result.prepayId());
        assertEquals(PAY_SIGN, result.paySign());
        assertEquals("prepay_id=PREPAY123", result.packageValue());
        HttpRequest request = sent.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("https", request.uri().getScheme());
        assertEquals("test.wsmsd.cn", request.uri().getHost());
        assertEquals("/sit/api/v3/labs/trans/preorder", request.uri().getPath());
        assertEquals(Duration.ofSeconds(8), request.timeout().orElseThrow());
        assertEquals("application/json; charset=utf-8", request.headers().firstValue("Content-Type").orElseThrow());
        byte[] published = publishedBody(request);
        String authorization = request.headers().firstValue("Authorization").orElseThrow();
        String signature = authorization.substring(authorization.indexOf("signature=\"") + 11,
                authorization.length() - 1);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(merchant.getPublic());
        verifier.update(join(APP + "\n" + MERCHANT_SERIAL + "\n1727499000\nAbCd12345678\n",
                published, "\n"));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)));
        assertTrue(new String(published, StandardCharsets.UTF_8).contains(OPEN_ID));
    }

    @Test
    void signedButMismatchedWechatAppOrPackageCannotBecomeUsablePaymentParameters() {
        for (String[] values : List.of(new String[] {"different-app", "prepay_id=PREPAY123"},
                new String[] {SUB_APP, "prepay_id=OTHER"})) {
            AtomicInteger calls = new AtomicInteger();
            LakalaHttpClient client = client(request -> {
                calls.incrementAndGet();
                return signed(200, preorderResponse(values[0], values[1]));
            });
            ChannelUnknownException failure = assertThrows(ChannelUnknownException.class,
                    () -> client.preorder(preorder(), NONCE));
            assertEquals(Reason.CHANNEL_RESPONSE, failure.reason());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void rejectsDuplicateHeadersAlteredRawBodyAndOversizeBody() throws Exception {
        byte[] body = preorderResponse(SUB_APP, "prepay_id=PREPAY123");
        Map<String, List<String>> duplicate = new LinkedHashMap<>(signedHeaders(body));
        duplicate.put("Lklapi-Signature", List.of(duplicate.get("Lklapi-Signature").getFirst(), "second"));
        assertReason(Reason.RESPONSE_SIGNATURE, client(request ->
                new WireResponse(200, headers(duplicate), new ByteArrayInputStream(body))));

        byte[] changed = new String(body, StandardCharsets.UTF_8).concat(" ")
                .getBytes(StandardCharsets.UTF_8);
        assertReason(Reason.RESPONSE_SIGNATURE, client(request ->
                new WireResponse(200, headers(signedHeaders(body)), new ByteArrayInputStream(changed))));

        byte[] huge = new byte[65_537];
        assertReason(Reason.RESPONSE_TOO_LARGE, client(request ->
                new WireResponse(200, headers(signedHeaders(body)), new ByteArrayInputStream(huge))));
    }

    @Test
    void realJdkExchangeBoundsBodyAfterHeadersAlreadyArrive() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", request -> {
            calls.incrementAndGet();
            request.getRequestBody().readAllBytes();
            request.sendResponseHeaders(200, 0);
            try (var response = request.getResponseBody()) {
                response.write('{');
                response.flush();
                releaseBody.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            LakalaHttpClient client = loopbackClient(server, http);
            long start = System.nanoTime();
            ChannelUnknownException failure = assertThrows(ChannelUnknownException.class,
                    () -> client.preorder(preorder(), NONCE));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(Reason.IO, failure.reason());
            assertTrue(elapsedMillis >= 7_000, "response ended before the deadline was exercised");
            assertTrue(elapsedMillis < 11_000, "headers must not end the full-response deadline");
            assertEquals(1, calls.get());
            assertSanitized(failure);
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void realJdkExchangeRejectsOversizeAndRepeatedSignatureHeaders() throws Exception {
        AtomicReference<String> mode = new AtomicReference<>("oversize");
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", request -> {
            calls.incrementAndGet();
            request.getRequestBody().readAllBytes();
            byte[] body = "oversize".equals(mode.get()) ? new byte[65_537]
                    : preorderResponse(SUB_APP, "prepay_id=PREPAY123");
            for (var entry : signedHeaders(body).entrySet()) {
                request.getResponseHeaders().add(entry.getKey(), entry.getValue().getFirst());
            }
            if ("duplicate".equals(mode.get())) {
                request.getResponseHeaders().add("Lklapi-Signature", "another-signature");
            }
            request.sendResponseHeaders(200, body.length);
            try (var response = request.getResponseBody()) { response.write(body); }
        });
        server.start();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            LakalaHttpClient client = loopbackClient(server, http);
            assertReason(Reason.RESPONSE_TOO_LARGE, client);
            mode.set("duplicate");
            assertReason(Reason.RESPONSE_SIGNATURE, client);
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectServerErrorAndTransportFailureRemainUnknownWithoutRetryOrSecrets() throws Exception {
        for (int status : List.of(302, 503)) {
            AtomicInteger calls = new AtomicInteger();
            LakalaHttpClient client = client(request -> {
                calls.incrementAndGet();
                return signed(status, "SECRET_RESPONSE_BODY".getBytes(StandardCharsets.UTF_8));
            });
            ChannelUnknownException failure = assertThrows(ChannelUnknownException.class,
                    () -> client.preorder(preorder(), NONCE));
            assertEquals(Reason.HTTP_STATUS, failure.reason());
            assertEquals(1, calls.get());
            assertSanitized(failure);
        }

        AtomicInteger calls = new AtomicInteger();
        LakalaHttpClient client = client(request -> {
            calls.incrementAndGet();
            throw new IOException("SECRET_RESPONSE_BODY " + OPEN_ID);
        });
        ChannelUnknownException failure = assertThrows(ChannelUnknownException.class,
                () -> client.preorder(preorder(), NONCE));
        assertEquals(Reason.IO, failure.reason());
        assertEquals(1, calls.get());
        assertSanitized(failure);
    }

    @Test
    void neverPerformsChannelIoInsideTransactionOrSynchronization() {
        AtomicInteger calls = new AtomicInteger();
        LakalaHttpClient client = client(request -> {
            calls.incrementAndGet();
            return signed(200, preorderResponse(SUB_APP, "prepay_id=PREPAY123"));
        });
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, () -> client.preorder(preorder(), NONCE));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        try {
            TransactionSynchronizationManager.initSynchronization();
            assertThrows(IllegalStateException.class, () -> client.preorder(preorder(), NONCE));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals(0, calls.get());
    }

    @Test
    void queryProcessingStateAndCloseAckDoNotAssertPaymentSuccess() throws Exception {
        LakalaHttpClient query = client(request -> signed(200, ("{\"code\":\"BBS00000\",\"resp_data\":{"
                + "\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\","
                + "\"trade_no\":\"T01\",\"trade_state\":\"DEAL\","
                + "\"total_amount\":\"123\",\"payer_amount\":\"\","
                + "\"account_type\":\"WECHAT\"}}")
                .getBytes(StandardCharsets.UTF_8)));
        var result = query.query(new QueryInput("OP123", at(), "123456", "TERM1", "2100001",
                LocalDate.of(2026, 9, 28)), new ExpectedPayment("123456", "2100001", 123), NONCE);
        assertFalse(result.isSuccess());
        assertNull(result.payerAmountCents());

        LakalaHttpClient close = client(request -> signed(200, ("{\"code\":\"BBS00000\",\"resp_data\":{"
                + "\"origin_out_trade_no\":\"2100001\",\"origin_trade_no\":\"T01\","
                + "\"trade_time\":\"20260928123456\"}}")
                .getBytes(StandardCharsets.UTF_8)));
        var acknowledgement = close.close(new CloseInput("OP123", at(), "123456", "TERM1",
                "2100001", "127.0.0.1"), NONCE);
        assertEquals("2100001", acknowledgement.originOutTradeNo());
    }

    @Test
    void diagnosticStringsCannotExposeCredentialsOpenIdOrWechatPaymentParameters() {
        assertFalse(credentials().toString().contains(MERCHANT_SERIAL));
        assertFalse(preorder().toString().contains(OPEN_ID));
        var result = client(request -> signed(200, preorderResponse(SUB_APP,
                "prepay_id=PREPAY123"))).preorder(preorder(), NONCE);
        assertFalse(result.toString().contains("PREPAY123"));
        assertFalse(result.toString().contains(PAY_SIGN));
    }

    private static LakalaHttpClient client(LakalaHttpClient.Exchange exchange) {
        return new LakalaHttpClient(Environment.SIT, credentials(), exchange);
    }

    private static LakalaHttpClient loopbackClient(HttpServer server, HttpClient http) {
        LakalaHttpClient.Exchange realJdk = LakalaHttpClient.jdkExchange(http);
        return client(officialRequest -> {
            URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + officialRequest.uri().getPath());
            HttpRequest.Builder copy = HttpRequest.newBuilder(local)
                    .timeout(officialRequest.timeout().orElseThrow());
            officialRequest.headers().map().forEach((name, values) ->
                    values.forEach(value -> copy.header(name, value)));
            return realJdk.send(copy.method(officialRequest.method(),
                    officialRequest.bodyPublisher().orElseThrow()).build());
        });
    }

    private static Credentials credentials() {
        return new Credentials(APP, MERCHANT_SERIAL, merchant.getPrivate(), PLATFORM_SERIAL,
                platform.getPublic());
    }

    private static PreorderInput preorder() {
        return new PreorderInput("OP123", at(), "123456", "TERM1", "2100001",
                new BigDecimal("1.23"), "Pet service", SUB_APP, OPEN_ID,
                "127.0.0.1", "https://example.test/notify");
    }

    private static LocalDateTime at() { return LocalDateTime.of(2026, 9, 28, 10, 11, 12); }

    private static byte[] preorderResponse(String appId, String packageValue) {
        return ("{\"code\":\"BBS00000\",\"resp_data\":{"
                + "\"merchant_no\":\"123456\",\"out_trade_no\":\"2100001\","
                + "\"trade_no\":\"T01\",\"acc_resp_fields\":{"
                + "\"app_id\":\"" + appId + "\",\"prepay_id\":\"PREPAY123\","
                + "\"pay_sign\":\"" + PAY_SIGN + "\",\"time_stamp\":\"1727499000\","
                + "\"nonce_str\":\"AbCd12345678\",\"package\":\"" + packageValue + "\","
                + "\"sign_type\":\"RSA\"}}}").getBytes(StandardCharsets.UTF_8);
    }

    private static WireResponse signed(int status, byte[] body) {
        return new WireResponse(status, headers(signedHeaders(body)), new ByteArrayInputStream(body));
    }

    private static Map<String, List<String>> signedHeaders(byte[] body) {
        try {
            String stamp = "1727499000", nonce = "AbCd12345678";
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(platform.getPrivate());
            signer.update(join(APP + "\n" + PLATFORM_SERIAL + "\n" + stamp + "\n" + nonce + "\n",
                    body, "\n"));
            Map<String, List<String>> values = new LinkedHashMap<>();
            values.put("Lklapi-Appid", List.of(APP));
            values.put("Lklapi-Serial", List.of(PLATFORM_SERIAL));
            values.put("Lklapi-Timestamp", List.of(stamp));
            values.put("Lklapi-Nonce", List.of(nonce));
            values.put("Lklapi-Signature", List.of(Base64.getEncoder().encodeToString(signer.sign())));
            return values;
        } catch (Exception failure) {
            throw new IllegalStateException("Test response signing failed", failure);
        }
    }

    private static HttpHeaders headers(Map<String, List<String>> fields) {
        return HttpHeaders.of(fields, (name, value) -> true);
    }

    private static byte[] join(String prefix, byte[] body, String suffix) {
        byte[] first = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] last = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] joined = new byte[first.length + body.length + last.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(body, 0, joined, first.length, body.length);
        System.arraycopy(last, 0, joined, first.length + body.length, last.length);
        return joined;
    }

    private static byte[] publishedBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }
            @Override public void onError(Throwable failure) {
                error.set(failure);
                complete.countDown();
            }
            @Override public void onComplete() { complete.countDown(); }
        });
        assertTrue(complete.await(2, TimeUnit.SECONDS));
        assertNull(error.get());
        return output.toByteArray();
    }

    private static void assertReason(Reason reason, LakalaHttpClient client) {
        ChannelUnknownException failure = assertThrows(ChannelUnknownException.class,
                () -> client.preorder(preorder(), NONCE));
        assertEquals(reason, failure.reason());
        assertSanitized(failure);
    }

    private static void assertSanitized(Throwable failure) {
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(OPEN_ID));
        assertFalse(failure.toString().contains("SECRET_RESPONSE_BODY"));
    }
}
