package com.petplatform.payment.biz.infrastructure.provider;

import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseAcknowledgement;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreparedRequest;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One application-level signed exchange. There is no Spring bean or automatic channel retry. */
public final class LakalaHttpClient implements PaymentChannel {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(8);

    public enum Environment {
        SIT("https://test.wsmsd.cn/sit/"),
        PRODUCTION("https://s2.lakala.com/");

        private final URI base;
        Environment(String base) { this.base = URI.create(base); }
    }

    public record Credentials(String appId, String merchantSerial, PrivateKey merchantPrivateKey,
            String platformSerial, PublicKey trustedPlatformKey) {
        public Credentials {
            if (blank(appId) || blank(merchantSerial) || merchantPrivateKey == null
                    || blank(platformSerial) || trustedPlatformKey == null) {
                throw new IllegalArgumentException("Lakala credentials are incomplete");
            }
        }
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    public record RequestNonce(String timestampSeconds, String nonce) {
        @Override public String toString() { return "RequestNonce[redacted]"; }
    }

    public enum Reason { IO, INTERRUPTED, HTTP_STATUS, RESPONSE_TOO_LARGE,
        RESPONSE_SIGNATURE, CHANNEL_RESPONSE }

    /** Unknown channel outcome: the caller must retain the original payment number and query. */
    public static final class ChannelUnknownException extends RuntimeException {
        private final Reason reason;
        ChannelUnknownException(Reason reason) {
            super("Lakala outcome unknown: " + reason);
            this.reason = reason;
        }
        public Reason reason() { return reason; }
    }

    @FunctionalInterface
    interface Exchange {
        WireResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    record WireResponse(int status, HttpHeaders headers, InputStream body) {}

    private final Environment environment;
    private final Credentials credentials;
    private final Exchange exchange;

    /** Requires the JVM retry switch at launch, before java.net.http initialization. */
    public LakalaHttpClient(Environment environment, Credentials credentials) {
        this(environment, credentials, productionExchange());
    }

    /** Package-only injection lets tests inspect the official URI without enabling arbitrary URLs. */
    LakalaHttpClient(Environment environment, Credentials credentials, Exchange exchange) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    public PreorderResult preorder(PreorderInput input, RequestNonce nonce) {
        return submitPreorder(input, nonce).result();
    }

    @Override public VerifiedPreorder submitPreorder(PreorderInput input, RequestNonce nonce) {
        noTransaction();
        PreparedRequest prepared = LakalaProtocol.preparePreorder(input);
        RawResponse response = send(prepared, nonce);
        try {
            PreorderResult result = LakalaProtocol.verifyPreorderResponse(response.headers(), response.body(),
                    credentials.appId(), credentials.platformSerial(),
                    credentials.trustedPlatformKey(), expected(input.merchantNo(),
                            input.outTradeNo(), input.amount()), input.subAppId());
            return new VerifiedPreorder(result, sha256(response.body()));
        } catch (LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public QueryResult query(QueryInput input, ExpectedPayment expected, RequestNonce nonce) {
        return lookup(input, expected, nonce).result();
    }

    @Override public VerifiedQuery lookup(QueryInput input, ExpectedPayment expected, RequestNonce nonce) {
        noTransaction();
        PreparedRequest prepared = LakalaProtocol.prepareQuery(input);
        if (!input.merchantNo().equals(expected.merchantNo())
                || !input.outTradeNo().equals(expected.outTradeNo())) {
            throw new IllegalArgumentException("Lakala query identity does not match expected payment");
        }
        RawResponse response = send(prepared, nonce);
        try {
            QueryResult result = LakalaProtocol.verifyQueryResponse(response.headers(), response.body(),
                    credentials.appId(), credentials.platformSerial(),
                    credentials.trustedPlatformKey(), expected);
            return new VerifiedQuery(result, sha256(response.body()));
        } catch (LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public CloseAcknowledgement close(CloseInput input, RequestNonce nonce) {
        return requestClose(input, nonce).result();
    }

    @Override public VerifiedClose requestClose(CloseInput input, RequestNonce nonce) {
        noTransaction();
        PreparedRequest prepared = LakalaProtocol.prepareClose(input);
        RawResponse response = send(prepared, nonce);
        try {
            CloseAcknowledgement result = LakalaProtocol.verifyCloseResponse(response.headers(), response.body(),
                    credentials.appId(), credentials.platformSerial(),
                    credentials.trustedPlatformKey(), input.originOutTradeNo());
            return new VerifiedClose(result, sha256(response.body()));
        } catch (LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    private static ExpectedPayment expected(String merchantNo, String paymentNo,
            java.math.BigDecimal amount) {
        try {
            return new ExpectedPayment(merchantNo, paymentNo,
                    amount.movePointRight(2).longValueExact());
        } catch (ArithmeticException | NullPointerException failure) {
            throw new IllegalArgumentException("Lakala expected amount is invalid");
        }
    }

    private record RawResponse(byte[] body, Map<String, String> headers) {}

    private RawResponse send(PreparedRequest prepared, RequestNonce nonce) {
        if (nonce == null) throw new IllegalArgumentException("Lakala request nonce is absent");
        byte[] body = prepared.rawBody();
        // Sign precisely the bytes handed to BodyPublisher. Never log request or authorization.
        String authorization = LakalaProtocol.authorization(credentials.appId(),
                credentials.merchantSerial(), nonce.timestampSeconds(), nonce.nonce(), body,
                credentials.merchantPrivateKey());
        String path = prepared.path();
        if (!List.of("/api/v3/labs/trans/preorder", "/api/v3/labs/query/tradequery",
                "/api/v3/labs/relation/close").contains(path)) {
            throw new IllegalArgumentException("Unsupported Lakala endpoint");
        }
        URI target = environment.base.resolve(path.substring(1));
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(RESPONSE_TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        final WireResponse response;
        try {
            response = exchange.send(request);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unknown(Reason.INTERRUPTED);
        } catch (IOException | RuntimeException failure) {
            throw unknown(hasLimitFailure(failure) ? Reason.RESPONSE_TOO_LARGE : Reason.IO);
        }
        if (response == null || response.body() == null || response.headers() == null)
            throw unknown(Reason.IO);
        try (InputStream stream = response.body()) {
            if (response.status() != 200) throw unknown(Reason.HTTP_STATUS);
            byte[] raw = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (raw.length > MAX_RESPONSE_BYTES) throw unknown(Reason.RESPONSE_TOO_LARGE);
            Map<String, String> headers = headerMap(response.headers());
            // The verifier runs over raw bytes, before any JSON response parser.
            LakalaProtocol.verifyResponse(headers, raw,
                    credentials.appId(), credentials.platformSerial(),
                    credentials.trustedPlatformKey());
            return new RawResponse(raw, headers);
        } catch (ChannelUnknownException failure) {
            throw failure;
        } catch (LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.RESPONSE_SIGNATURE);
        } catch (IOException | RuntimeException failure) {
            throw unknown(Reason.IO);
        }
    }

    private static Map<String, String> headerMap(HttpHeaders headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : List.of("Lklapi-Appid", "Lklapi-Serial", "Lklapi-Timestamp",
                "Lklapi-Nonce", "Lklapi-Signature")) values.put(name, responseHeader(headers, name));
        return values;
    }

    private static String responseHeader(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.size() != 1 || blank(values.getFirst()) || values.getFirst().indexOf(',') >= 0)
            throw unknown(Reason.RESPONSE_SIGNATURE);
        return values.getFirst();
    }

    private static Exchange productionExchange() {
        if (!"true".equalsIgnoreCase(launchProperty("jdk.httpclient.disableRetryConnect"))
                || !"true".equalsIgnoreCase(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || "true".equalsIgnoreCase(launchProperty("jdk.httpclient.enableAllMethodRetry"))
                || "true".equalsIgnoreCase(System.getProperty("jdk.httpclient.enableAllMethodRetry"))) {
            throw new IllegalStateException("Lakala HTTP client requires JVM retry controls at launch");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return jdkExchange(http);
    }

    /** Package-only access to the same bounded JDK transport for local loopback tests. */
    static Exchange jdkExchange(HttpClient http) {
        Objects.requireNonNull(http, "http");
        return request -> {
            // BodySubscriber completes only after all bytes. This independent wall budget also
            // bounds servers that send headers promptly and then stall the response body.
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                    info -> new BoundedBodySubscriber());
            try {
                HttpResponse<byte[]> response = pending.get(RESPONSE_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS);
                return new WireResponse(response.statusCode(), response.headers(),
                        new ByteArrayInputStream(response.body()));
            } catch (TimeoutException | ExecutionException failure) {
                pending.cancel(true);
                if (hasLimitFailure(failure)) throw new ResponseLimitException();
                throw new IOException("Lakala exchange incomplete");
            } catch (InterruptedException failure) {
                pending.cancel(true);
                throw failure;
            }
        };
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE_BYTES - body.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseLimitException());
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }

    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Lakala network request requires no active transaction");
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String launchProperty(String name) {
        String prefix = "-D" + name + "=";
        String last = null;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith(prefix)) last = arg.substring(prefix.length());
        }
        return last;
    }
    private static boolean hasLimitFailure(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            if (at instanceof ResponseLimitException) return true;
        }
        return false;
    }
    private static final class ResponseLimitException extends IOException {
        private ResponseLimitException() { super("Lakala response exceeded limit"); }
    }
    private static ChannelUnknownException unknown(Reason reason) {
        return new ChannelUnknownException(reason);
    }
    private static String sha256(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
