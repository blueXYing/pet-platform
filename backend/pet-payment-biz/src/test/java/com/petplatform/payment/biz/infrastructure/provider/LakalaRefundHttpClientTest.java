package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundHttpClient.Reason;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundProtocol.State;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
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

class LakalaRefundHttpClientTest {
    private static final RequestNonce NONCE = new RequestNonce("1727499000", "AbCd12345678");
    private static KeyPair merchant;

    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchant = generator.generateKeyPair();
    }

    @Test void signsPublishedBytesAndUsesOnlyOfficialRefundAndQueryAddresses() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        byte[] body = LakalaRefundProtocolTest.body("PROCESSING", "123", "123",
                "20260928101112", "CHANNEL-1");
        var client = client(request -> {
            sent.set(request);
            return signed(request.uri().getPath().endsWith("refund_query")
                    ? LakalaRefundProtocolTest.body("DEAL", "123", "123",
                            "20260928101112", "CHANNEL-1") : body);
        });
        var result = client.submit(LakalaRefundProtocolTest.input(), NONCE);
        assertEquals(State.PROCESSING, result.result().state());
        assertEquals("test.wsmsd.cn", sent.get().uri().getHost());
        assertEquals("/sit" + LakalaRefundProtocol.REFUND_PATH, sent.get().uri().getPath());
        verifyPublishedSignature(sent.get());
        var query = client.query(LakalaRefundProtocolTest.query(),
                LakalaRefundProtocolTest.expected(), NONCE);
        assertEquals(State.DEAL, query.result().state());
        assertEquals("/sit" + LakalaRefundProtocol.QUERY_PATH, sent.get().uri().getPath());
    }

    @Test void loopbackJdkExchangeAndTamperingRemainOfflineAndFailClosed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] success = LakalaRefundProtocolTest.body("SUCCESS", "123", "123",
                "20260928101112", "CHANNEL-1");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            for (var entry : LakalaRefundProtocolTest.headers(success).entrySet()) {
                exchange.getResponseHeaders().add(entry.getKey(), entry.getValue());
            }
            exchange.sendResponseHeaders(200, success.length);
            try (var response = exchange.getResponseBody()) { response.write(success); }
        });
        server.start();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var real = LakalaHttpClient.jdkExchange(http);
            var client = client(official -> {
                URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + official.uri().getPath());
                HttpRequest.Builder builder = HttpRequest.newBuilder(local)
                        .timeout(official.timeout().orElseThrow());
                official.headers().map().forEach((name, values) ->
                        values.forEach(value -> builder.header(name, value)));
                return real.send(builder.method(official.method(),
                        official.bodyPublisher().orElseThrow()).build());
            });
            assertTrue(client.submit(LakalaRefundProtocolTest.input(), NONCE).result().isSuccess());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }

        byte[] changed = LakalaRefundProtocolTest.body("SUCCESS", "124", "123",
                "20260928101112", "CHANNEL-1");
        var tampered = client(request -> new WireResponse(200,
                httpHeaders(LakalaRefundProtocolTest.headers(success)), new ByteArrayInputStream(changed)));
        ChannelUnknownException failure = assertThrows(ChannelUnknownException.class, () ->
                tampered.submit(LakalaRefundProtocolTest.input(), NONCE));
        assertEquals(Reason.RESPONSE_SIGNATURE, failure.reason());
        assertNull(failure.getCause());
    }

    @Test void rejectsActiveTransactionBeforeAnyNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        var client = client(request -> { calls.incrementAndGet(); throw new AssertionError(); });
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, () ->
                    client.submit(LakalaRefundProtocolTest.input(), NONCE));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertEquals(0, calls.get());
    }

    private static LakalaRefundHttpClient client(LakalaHttpClient.Exchange exchange) {
        return new LakalaRefundHttpClient(Environment.SIT,
                new Credentials("offline-app", "offline-merchant-serial", merchant.getPrivate(),
                        "offline-platform-serial", LakalaRefundProtocolTest.platform().getPublic()),
                exchange);
    }

    private static WireResponse signed(byte[] body) {
        return new WireResponse(200, httpHeaders(LakalaRefundProtocolTest.headers(body)),
                new ByteArrayInputStream(body));
    }

    private static HttpHeaders httpHeaders(Map<String, String> values) {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        values.forEach((name, value) -> lists.put(name, List.of(value)));
        return HttpHeaders.of(lists, (name, value) -> true);
    }

    private static void verifyPublishedSignature(HttpRequest request) throws Exception {
        byte[] body = published(request);
        String auth = request.headers().firstValue("Authorization").orElseThrow();
        String encoded = auth.substring(auth.indexOf("signature=\"") + 11, auth.length() - 1);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(merchant.getPublic());
        verifier.update(("offline-app\noffline-merchant-serial\n1727499000\nAbCd12345678\n")
                .getBytes(StandardCharsets.UTF_8));
        verifier.update(body);
        verifier.update((byte) '\n');
        assertTrue(verifier.verify(Base64.getDecoder().decode(encoded)));
    }

    private static byte[] published(HttpRequest request) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
            }
            @Override public void onError(Throwable failure) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        return output.toByteArray();
    }
}
