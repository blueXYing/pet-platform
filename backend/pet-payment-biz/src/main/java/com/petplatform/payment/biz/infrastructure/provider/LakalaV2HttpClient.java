package com.petplatform.payment.biz.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Exchange;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.PreparedV2Request;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ReceiverActionResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ReceiverDetailResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.RelationApplyPageResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.V2Envelope;
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
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Signed Lakala Open Platform v2 HTTP client (1004 5-line signature) shared by Stage 4 MMS
 * split-receiver onboarding (1084/1287/1288/1289/1290/1872) and reusable by Stage 5 LAEP
 * wallet/withdrawal (1269/1319/1271) via {@link #postV2(String, Object, long, String, RequestNonce)}.
 *
 * <p>Enforces: no active DB transaction, 65,536B response cap, fixed endpoint path whitelist,
 * JVM retry guard at launch, and raw-byte response signature verification before JSON parsing.
 */
public final class LakalaV2HttpClient {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    static final Set<String> ALLOWED_PATHS = Set.of(
            LakalaV2Protocol.UPLOAD_FILE_PATH,
            LakalaV2Protocol.APPLY_RECEIVER_PATH,
            LakalaV2Protocol.MODIFY_RECEIVER_PATH,
            LakalaV2Protocol.QUERY_RECEIVER_PATH,
            LakalaV2Protocol.APPLY_BIND_PATH,
            LakalaV2Protocol.LIST_RELATION_APPLY_PATH,
            LakalaV2Protocol.EWALLET_BALANCE_QUERY_PATH,
            LakalaV2Protocol.EWALLET_WITHDRAW_D1_PATH,
            LakalaV2Protocol.EWALLET_WITHDRAW_QUERY_PATH);

    public record VerifiedV2Response(
            String retCode,
            String retMsg,
            JsonNode respData,
            byte[] rawBody,
            String rawResponseSha256) {
        public VerifiedV2Response {
            rawBody = rawBody == null ? new byte[0] : rawBody.clone();
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }

        public boolean isSuccess() {
            return LakalaV2Protocol.SUCCESS_CODE.equals(retCode);
        }

        @Override
        public String toString() {
            return "VerifiedV2Response[retCode=" + retCode + "]";
        }
    }

    public record VerifiedUploadFile(UploadFileResult result, String rawResponseSha256) {}

    public record VerifiedReceiverAction(ReceiverActionResult result, String rawResponseSha256) {}

    public record VerifiedReceiverDetail(ReceiverDetailResult result, String rawResponseSha256) {}

    public record VerifiedApplyBind(ApplyBindResult result, String rawResponseSha256) {}

    public record VerifiedRelationApplyPage(RelationApplyPageResult result, String rawResponseSha256) {}

    public enum Reason {
        IO, INTERRUPTED, HTTP_STATUS, RESPONSE_TOO_LARGE, RESPONSE_SIGNATURE, CHANNEL_RESPONSE
    }

    /** Unknown channel outcome (transport, HTTP status, oversized body, signature, or malformed JSON). */
    public static final class ChannelUnknownException extends RuntimeException {
        private final Reason reason;

        private ChannelUnknownException(Reason reason) {
            super("Lakala v2 outcome unknown: " + reason);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Signed HTTP 200 v2 response carrying a deterministic non-{@code 000000} business error code. */
    public static final class ChannelRejectedException extends RuntimeException {
        private final String retCode;
        private final String retMsg;

        public ChannelRejectedException(String retCode, String retMsg) {
            super("Lakala v2 rejected request: retCode=" + retCode
                    + (retMsg == null || retMsg.isBlank() ? "" : " retMsg=" + retMsg));
            this.retCode = retCode;
            this.retMsg = retMsg;
        }

        public String retCode() {
            return retCode;
        }

        public String retMsg() {
            return retMsg;
        }
    }

    private final Environment environment;
    private final Credentials credentials;
    private final Exchange exchange;

    public LakalaV2HttpClient(Environment environment, Credentials credentials) {
        this(environment, credentials, productionExchange());
    }

    /** Package-private injection supports offline unit tests; endpoint whitelist remains enforced. */
    LakalaV2HttpClient(Environment environment, Credentials credentials, Exchange exchange) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    // ---- Generic Reusable v2 Exchange (shared by Stage 4 & Stage 5) ------------------------

    /**
     * Wraps {@code reqData} into a Lakala v2 envelope, signs with 1004 5-line RSA-SHA256, verifies
     * the 1004 response signature, and parses {@code retCode}/{@code retMsg}/{@code respData}.
     */
    public VerifiedV2Response postV2(String path, Object reqData, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        if (!ALLOWED_PATHS.contains(path)) {
            throw new IllegalArgumentException("Unsupported Lakala v2 endpoint: " + path);
        }
        PreparedV2Request prepared = LakalaV2Protocol.buildV2Request(path, reqData, timestampMillis, reqId);
        return postV2(prepared, nonce);
    }

    /** Sends an already-prepared v2 request with full 1004 request signing & response verification. */
    public VerifiedV2Response postV2(PreparedV2Request prepared, RequestNonce nonce) {
        noTransaction();
        if (prepared == null) throw new IllegalArgumentException("Prepared Lakala v2 request absent");
        byte[] raw = send(prepared, nonce);
        try {
            V2Envelope envelope = LakalaV2Protocol.parseV2Envelope(raw);
            return new VerifiedV2Response(envelope.retCode(), envelope.retMsg(),
                    envelope.respData(), raw, sha256(raw));
        } catch (LakalaV2Protocol.ProtocolException | LakalaProtocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    // ---- Stage 4 Typed MMS Methods ---------------------------------------------------------

    public VerifiedUploadFile uploadFile(UploadFileInput input, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        PreparedV2Request request = LakalaV2Protocol.prepareUploadFile(input, timestampMillis, reqId);
        VerifiedV2Response response = requireSuccess(postV2(request, nonce));
        try {
            UploadFileResult result = LakalaV2Protocol.parseUploadFileRespData(
                    response.respData(), input.orderNo(), input.attType());
            return new VerifiedUploadFile(result, response.rawResponseSha256());
        } catch (LakalaV2Protocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public VerifiedReceiverAction applyLedgerReceiver(ApplyReceiverInput input, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        PreparedV2Request request = LakalaV2Protocol.prepareApplyReceiver(input, timestampMillis, reqId);
        VerifiedV2Response response = requireSuccess(postV2(request, nonce));
        try {
            ReceiverActionResult result = LakalaV2Protocol.parseApplyReceiverRespData(
                    response.respData(), input.orderNo());
            return new VerifiedReceiverAction(result, response.rawResponseSha256());
        } catch (LakalaV2Protocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public VerifiedReceiverAction modifyLedgerReceiver(ModifyReceiverInput input, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        PreparedV2Request request = LakalaV2Protocol.prepareModifyReceiver(input, timestampMillis, reqId);
        VerifiedV2Response response = requireSuccess(postV2(request, nonce));
        try {
            ReceiverActionResult result = LakalaV2Protocol.parseModifyReceiverRespData(
                    response.respData(), input.receiverNo());
            return new VerifiedReceiverAction(result, response.rawResponseSha256());
        } catch (LakalaV2Protocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public VerifiedReceiverDetail queryReceiverDetail(QueryReceiverInput input, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        PreparedV2Request request = LakalaV2Protocol.prepareQueryReceiver(input, timestampMillis, reqId);
        VerifiedV2Response response = requireSuccess(postV2(request, nonce));
        try {
            ReceiverDetailResult result = LakalaV2Protocol.parseQueryReceiverRespData(
                    response.respData(), input.receiverNo());
            return new VerifiedReceiverDetail(result, response.rawResponseSha256());
        } catch (LakalaV2Protocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public VerifiedApplyBind applyBind(ApplyBindInput input, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        PreparedV2Request request = LakalaV2Protocol.prepareApplyBind(input, timestampMillis, reqId);
        VerifiedV2Response response = requireSuccess(postV2(request, nonce));
        try {
            ApplyBindResult result = LakalaV2Protocol.parseApplyBindRespData(
                    response.respData(), input.orderNo());
            return new VerifiedApplyBind(result, response.rawResponseSha256());
        } catch (LakalaV2Protocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    public VerifiedRelationApplyPage listRelationApply(ListRelationApplyInput input, long timestampMillis,
            String reqId, RequestNonce nonce) {
        noTransaction();
        PreparedV2Request request = LakalaV2Protocol.prepareListRelationApply(input, timestampMillis, reqId);
        VerifiedV2Response response = requireSuccess(postV2(request, nonce));
        try {
            RelationApplyPageResult result = LakalaV2Protocol.parseListRelationApplyRespData(
                    response.respData());
            return new VerifiedRelationApplyPage(result, response.rawResponseSha256());
        } catch (LakalaV2Protocol.ProtocolException failure) {
            throw unknown(Reason.CHANNEL_RESPONSE);
        }
    }

    // ---- Wire Transport & Guards -----------------------------------------------------------

    private static VerifiedV2Response requireSuccess(VerifiedV2Response response) {
        if (!response.isSuccess()) {
            throw new ChannelRejectedException(response.retCode(), response.retMsg());
        }
        return response;
    }

    private byte[] send(PreparedV2Request prepared, RequestNonce nonce) {
        if (nonce == null) throw new IllegalArgumentException("Lakala request nonce absent");
        String path = prepared.path();
        if (!ALLOWED_PATHS.contains(path)) {
            throw new IllegalArgumentException("Unsupported Lakala v2 endpoint: " + path);
        }
        byte[] body = prepared.rawBody();
        String authorization = LakalaProtocol.authorization(credentials.appId(),
                credentials.merchantSerial(), nonce.timestampSeconds(), nonce.nonce(), body,
                credentials.merchantPrivateKey());
        String base = environment == Environment.SIT
                ? "https://test.wsmsd.cn/sit" : "https://s2.lakala.com";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        final WireResponse response;
        try {
            response = exchange.send(request);
        } catch (InterruptedException failure) {
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
            return raw;
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
            throw new IllegalStateException("Lakala v2 HTTP client requires JVM retry controls at launch");
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
            throw new IllegalStateException("Lakala v2 network request requires no active transaction");
        }
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ChannelUnknownException unknown(Reason reason) {
        return new ChannelUnknownException(reason);
    }
}
