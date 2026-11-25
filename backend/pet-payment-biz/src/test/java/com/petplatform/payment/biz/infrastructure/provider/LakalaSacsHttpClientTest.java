package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.Reason;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ReceiverShare;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Offline wire-level tests for {@link LakalaSacsHttpClient}. */
class LakalaSacsHttpClientTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 25, 11, 0, 0);
    private static final LocalDate LOG_DATE = LocalDate.of(2026, 11, 18);
    private static final RequestNonce NONCE = new RequestNonce("1764039600", "AbCd12345678");
    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
    }

    @Test
    void queryAmtSeparateAndQueryPlusHitOfficialSitUrisAndVerifySignatures() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>();
        byte[] amtBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_data\":{"
                + "\"merchant_no\":\"M1\",\"log_no\":\"66210306990190\",\"log_date\":\"20261118\","
                + "\"total_separate_amt\":\"9962\",\"can_separate_amt\":\"9962\"}}").getBytes(StandardCharsets.UTF_8);
        HttpHeaders amtHeaders = headers(amtBody);
        LakalaSacsHttpClient amtClient = client(request -> {
            uri.set(request.uri().toString());
            return new WireResponse(200, amtHeaders, new ByteArrayInputStream(amtBody));
        });
        var amt = amtClient.queryAmt(new QueryAmtInput(AT, "M1", "66210306990190", LOG_DATE), NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v3/sacs/queryAmt", uri.get());
        assertEquals(9962L, amt.result().canSeparateCents());
        assertTrue(amt.rawResponseSha256().matches("[0-9a-f]{64}"));

        byte[] sepBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_data\":{"
                + "\"separate_no\":\"SEP01\",\"out_separate_no\":\"SP01\",\"status\":\"PROCESSING\"}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpHeaders sepHeaders = headers(sepBody);
        LakalaSacsHttpClient sepClient = client(request -> {
            uri.set(request.uri().toString());
            return new WireResponse(200, sepHeaders, new ByteArrayInputStream(sepBody));
        });
        var sep = sepClient.separate(new SeparateInput(AT, "M1", "66210306990190", LOG_DATE, "SP01", 9940L,
                null, null, List.of(ReceiverShare.toReceiver("SR1", 9940L)), null), NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v3/sacs/separate", uri.get());
        assertEquals("SEP01", sep.result().separateNo());
        assertEquals(SplitStatus.PROCESSING, sep.result().status());

        byte[] queryBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_data\":{"
                + "\"separate_no\":\"SEP01\",\"out_separate_no\":\"SP01\",\"cmd_type\":\"SEPARATE\","
                + "\"log_no\":\"66210306990190\",\"log_date\":\"20261118\",\"status\":\"SUCCESS\","
                + "\"final_status\":\"SUCCESS\",\"total_amt\":\"9940\"}}").getBytes(StandardCharsets.UTF_8);
        HttpHeaders queryHeaders = headers(queryBody);
        LakalaSacsHttpClient qClient = client(request -> {
            uri.set(request.uri().toString());
            return new WireResponse(200, queryHeaders, new ByteArrayInputStream(queryBody));
        });
        var out = qClient.queryPlus(new QueryPlusInput(AT, "M1", "SEP01", "SP01"),
                new ExpectedSplit("SP01", "SEP01", CmdType.SEPARATE, "66210306990190", LOG_DATE), NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v3/sacs/query_plus", uri.get());
        assertTrue(out.outcome().isSuccess());

        byte[] fbBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_data\":{"
                + "\"separate_no\":\"FBSEP01\",\"origin_separate_no\":\"SEP01\",\"out_separate_no\":\"FB01\","
                + "\"total_amt\":\"2982\",\"status\":\"PROCESSING\"}}").getBytes(StandardCharsets.UTF_8);
        HttpHeaders fbHeaders = headers(fbBody);
        LakalaSacsHttpClient fbClient = client(request -> {
            uri.set(request.uri().toString());
            return new WireResponse(200, fbHeaders, new ByteArrayInputStream(fbBody));
        });
        var fb = fbClient.fallback(new FallbackInput(AT, "M1", "SEP01", "SP01", "FB01", 2982L,
                "SR1", "REFUND:1"), NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v3/sacs/fallback", uri.get());
        assertEquals("FBSEP01", fb.result().separateNo());
        assertEquals(2982L, fb.result().totalAmountCents());
    }

    @Test
    void non200IoAndNonSacs0000AllBecomeChannelUnknown() throws Exception {
        LakalaSacsHttpClient ioFail = client(req -> { throw new IOException("timeout"); });
        ChannelUnknownException io = assertThrows(ChannelUnknownException.class,
                () -> ioFail.queryAmt(new QueryAmtInput(AT, "M1", "66210306990190", LOG_DATE), NONCE));
        assertEquals(Reason.IO, io.reason());

        byte[] errBody = "{\"code\":\"SACS9999\",\"msg\":\"处理中\"}".getBytes(StandardCharsets.UTF_8);
        HttpHeaders errHeaders = headers(errBody);
        LakalaSacsHttpClient bizErr = client(req -> new WireResponse(200, errHeaders,
                new ByteArrayInputStream(errBody)));
        ChannelUnknownException unknown = assertThrows(ChannelUnknownException.class,
                () -> bizErr.separate(new SeparateInput(AT, "M1", "66210306990190", LOG_DATE, "SP01", 100L,
                        null, null, List.of(ReceiverShare.toReceiver("SR1", 100L)), null), NONCE));
        assertEquals(Reason.CHANNEL_RESPONSE, unknown.reason());
    }

    private static LakalaSacsHttpClient client(LakalaHttpClient.Exchange exchange) {
        return new LakalaSacsHttpClient(Environment.SIT,
                new Credentials("app-1", "merchant-serial", keys.getPrivate(), "serial-1", keys.getPublic()),
                exchange);
    }

    private static HttpHeaders headers(byte[] body) throws Exception {
        String time = "1764039600", nonce = "AbCd12345678";
        byte[] prefix = ("app-1\nserial-1\n" + time + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[prefix.length + body.length + 1];
        System.arraycopy(prefix, 0, msg, 0, prefix.length);
        System.arraycopy(body, 0, msg, prefix.length, body.length);
        msg[msg.length - 1] = '\n';
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(msg);
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Lklapi-Appid", List.of("app-1"));
        map.put("Lklapi-Serial", List.of("serial-1"));
        map.put("Lklapi-Timestamp", List.of(time));
        map.put("Lklapi-Nonce", List.of(nonce));
        map.put("Lklapi-Signature", List.of(sig));
        return HttpHeaders.of(map, (k, v) -> true);
    }
}
