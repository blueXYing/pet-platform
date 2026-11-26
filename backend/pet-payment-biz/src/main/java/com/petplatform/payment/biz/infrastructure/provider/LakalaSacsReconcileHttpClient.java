package com.petplatform.payment.biz.infrastructure.provider;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Exchange;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.PreparedRequest;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListPage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Signed Lakala v3 SACS reconciliation HTTP client (1879 queryResultList & 1877 queryDetail).
 * Never executes inside an active DB transaction and restricts outbound endpoints to a fixed whitelist.
 */
public final class LakalaSacsReconcileHttpClient {
    private static final int MAX_RESPONSE_BYTES = 524_288;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> ALLOWED_PATHS = List.of(
            LakalaSacsReconcileProtocol.QUERY_RESULT_LIST_PATH,
            LakalaSacsReconcileProtocol.QUERY_DETAIL_PATH);

    public record VerifiedResultListPage(QueryResultListPage page, String rawResponseSha256) {}

    public record VerifiedDetailPage(QueryDetailPage page, String rawResponseSha256) {}

    public enum Reason {
        IO, INTERRUPTED, HTTP_STATUS, RESPONSE_TOO_LARGE, RESPONSE_SIGNATURE, CHANNEL_RESPONSE
    }

    public static final class ChannelUnknownException extends RuntimeException {
        private final Reason reason;

        private ChannelUnknownException(Reason reason) {
            super("Lakala SACS reconcile outcome unknown: " + reason);
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }

    private final Environment environment;
    private final Credentials credentials;
    private final Exchange exchange;

    public LakalaSacsReconcileHttpClient(Environment environment, Credentials credentials) {
        this(environment, credentials, productionExchange());
    }

    /** Package-only injection supports offline tests; target endpoints remain fixed. */
    LakalaSacsReconcileHttpClient(Environment environment, Credentials credentials, Exchange exchange) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    public VerifiedResultListPage queryResultList(QueryResultListInput input, RequestNonce nonce) {
        noTransaction();
        PreparedRequest request = LakalaSacsReconcileProtocol.prepareQueryResultList(input);
        RawResponse response = send(request, nonce);
        try {
            QueryResultListPage page = LakalaSacsReconcileProtocol.verifyQueryResultListResponse(
                    response.headers(), response.body(), credentials.appId(),
                    credentials.platformSerial(), credentials.trustedPlatformKey(),
                    input.merchantNo());
            return new VerifiedResultListPage(page, sha256(response.body()));
        } catch (LakalaSacsReconcileProtocol.ProtocolException | LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public VerifiedDetailPage queryDetail(QueryDetailInput input, RequestNonce nonce) {
        noTransaction();
        PreparedRequest request = LakalaSacsReconcileProtocol.prepareQueryDetail(input);
        RawResponse response = send(request, nonce);
        try {
            QueryDetailPage page = LakalaSacsReconcileProtocol.verifyQueryDetailResponse(
                    response.headers(), response.body(), credentials.appId(),
                    credentials.platformSerial(), credentials.trustedPlatformKey(),
                    input.merchantNo(), input.outSeparateNo());
            return new VerifiedDetailPage(page, sha256(response.body()));
        } catch (LakalaSacsReconcileProtocol.ProtocolException | LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    private record RawResponse(byte[] body, Map<String, String> headers) {}

    private RawResponse send(PreparedRequest prepared, RequestNonce nonce) {
        if (nonce == null) throw new IllegalArgumentException("Lakala request nonce absent");
        String path = prepared.path();
        if (!ALLOWED_PATHS.contains(path)) throw new IllegalArgumentException("Unsupported SACS reconcile endpoint");
        byte[] body = prepared.rawBody();
        String authorization = LakalaProtocol.authorization(credentials.appId(),
                credentials.merchantSerial(), nonce.timestampSeconds(), nonce.nonce(), body,
                credentials.merchantPrivateKey());
        String base = environment == Environment.SIT ? "https://test.wsmsd.cn/sit" : "https://s2.lakala.com";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        final WireResponse response;
        try { response = exchange.send(request); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unknown(Reason.INTERRUPTED);
        } catch (IOException | RuntimeException failure) {
            throw unknown(Reason.IO);
        }
        if (response == null || response.body() == null || response.headers() == null) {
            throw unknown(Reason.IO);
        }
        try (InputStream stream = response.body()) {
            if (response.status() != 200) throw unknown(Reason.HTTP_STATUS);
            byte[] raw = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (raw.length > MAX_RESPONSE_BYTES) throw unknown(Reason.RESPONSE_TOO_LARGE);
            Map<String, String> headers = headerMap(response.headers());
            LakalaProtocol.verifyResponse(headers, raw, credentials.appId(),
                    credentials.platformSerial(), credentials.trustedPlatformKey());
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
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : List.of("Lklapi-Appid", "Lklapi-Serial", "Lklapi-Timestamp",
                "Lklapi-Nonce", "Lklapi-Signature")) {
            List<String> values = headers.allValues(name);
            if (values.size() != 1 || values.getFirst().isBlank() || values.getFirst().contains(",")) {
                throw unknown(Reason.RESPONSE_SIGNATURE);
            }
            result.put(name, values.getFirst());
        }
        return result;
    }

    private static Exchange productionExchange() {
        if (!"true".equalsIgnoreCase(launchProperty("jdk.httpclient.disableRetryConnect"))
                || !"true".equalsIgnoreCase(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || "true".equalsIgnoreCase(launchProperty("jdk.httpclient.enableAllMethodRetry"))
                || "true".equalsIgnoreCase(System.getProperty("jdk.httpclient.enableAllMethodRetry"))) {
            throw new IllegalStateException("Lakala SACS reconcile HTTP client requires JVM retry controls at launch");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return LakalaHttpClient.jdkExchange(http);
    }

    private static String launchProperty(String name) {
        String prefix = "-D" + name + "=";
        String last = null;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith(prefix)) last = arg.substring(prefix.length());
        }
        return last;
    }

    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Lakala SACS reconcile request requires no active transaction");
        }
    }

    private static String sha256(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static ChannelUnknownException unknown(Reason reason) {
        return new ChannelUnknownException(reason);
    }
}
