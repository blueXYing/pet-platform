package com.petplatform.payment.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Credentials;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.Environment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.WireResponse;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.Reason;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.ProtocolException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Offline protocol & wire-level tests for Lakala 1879 (queryResultList) and 1877 (queryDetail). */
class LakalaSacsReconcileProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 25, 11, 0, 0);
    private static final RequestNonce NONCE = new RequestNonce("1764039600", "AbCd12345678");
    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
    }

    @Test
    void queryResultList1879EnvelopeValidationAndDateSpanBounds() throws Exception {
        var req = LakalaSacsReconcileProtocol.prepareQueryResultList(QueryResultListInput.byDateRange(
                AT, "8222900701105V2", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 25), 1, 50));
        assertEquals("/api/v3/sacs/queryResultList", req.path());
        JsonNode root = JSON.readTree(req.rawBody());
        assertEquals("20261125110000", root.path("req_time").textValue());
        assertEquals("3.0", root.path("version").textValue());
        JsonNode data = root.path("req_data");
        assertEquals("8222900701105V2", data.path("merchant_no").textValue());
        assertEquals("20261118", data.path("start_date").textValue());
        assertEquals("20261125", data.path("end_date").textValue());
        assertEquals(1, data.path("page_no").intValue());
        assertEquals(50, data.path("page_size").intValue());

        // > 7 days rejected
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 25), 1, 50)));
        // end < start rejected
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 24), 1, 50)));
        // page_size > 50 or <= 0 rejected
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 1, 51)));
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 1, 0)));
        // missing all 5 query conditions rejected
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryResultList(
                new QueryResultListInput(AT, "M1", null, null, null, null, null, null, null, null, 1, 20)));
        // query by out_separate_no alone is allowed (condition 1)
        var byOut = LakalaSacsReconcileProtocol.prepareQueryResultList(
                new QueryResultListInput(AT, null, null, null, "SP1001", null, null, null, null, null, 1, 10));
        assertEquals("SP1001", JSON.readTree(byOut.rawBody()).path("req_data").path("out_separate_no").textValue());
    }

    @Test
    void queryDetail1877EnvelopeValidationAndDateSpanBounds() throws Exception {
        // Exact 30-day span allowed
        var req = LakalaSacsReconcileProtocol.prepareQueryDetail(new QueryDetailInput(
                AT, "8222900701105V2", null, "SEPARATE", null, null,
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 25), null, 1, 50));
        assertEquals("/api/v3/sacs/queryDetail", req.path());
        JsonNode data = JSON.readTree(req.rawBody()).path("req_data");
        assertEquals("20261026", data.path("start_date").textValue());
        assertEquals("20261125", data.path("end_date").textValue());
        assertEquals("SEPARATE", data.path("cmd_type").textValue());

        // > 30 days rejected
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryDetail(
                new QueryDetailInput(AT, "M1", null, null, null, null,
                        LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 25), null, 1, 50)));
        // page_size > 50 rejected
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.prepareQueryDetail(
                QueryDetailInput.byOutSeparateNo(AT, "M1", "SP1001", 1, 51)));
    }

    @Test
    void verify1879And1877ResponsesWith1004SignatureAndBothStringAndIntNumbers() throws Exception {
        byte[] listBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_time\":\"20261125110001\","
                + "\"resp_data\":{\"total_page_num\":\"1\",\"total_data_num\":\"2\",\"data_num\":2,\"datas\":["
                + "{\"merchant_no\":\"8222900701105V2\",\"log_no\":\"20261125000001\","
                + "\"separate_no\":\"SEP001\",\"out_separate_no\":\"SP001\",\"separate_date\":\"20261125\","
                + "\"cmd_type\":\"SEPARATE\",\"recv_no_list\":[\"SR001\",\"8222900701105V2\"],"
                + "\"amt\":\"9962\",\"actual_amt\":\"9960\",\"total_fee_amt\":\"2\","
                + "\"fail_reason\":\"成功\",\"status\":\"SUCCESS\"},"
                + "{\"merchant_no\":\"8222900701105V2\",\"log_no\":\"20261125000002\","
                + "\"separate_no\":\"FBSEP001\",\"out_separate_no\":\"FB001\",\"separate_date\":\"20261125\","
                + "\"cmd_type\":\"FALLBACK\",\"amt\":2500,\"status\":\"FALLBACK_END\"}"
                + "]}}").getBytes(StandardCharsets.UTF_8);
        Map<String, String> listHeaders = headerMap(listBody);
        var page = LakalaSacsReconcileProtocol.verifyQueryResultListResponse(
                listHeaders, listBody, "app-1", "serial-1", keys.getPublic(), "8222900701105V2");
        assertEquals(1, page.totalPageNum());
        assertEquals(2, page.totalDataNum());
        assertEquals(2, page.datas().size());
        var item0 = page.datas().get(0);
        assertEquals("SP001", item0.outSeparateNo());
        assertEquals(CmdType.SEPARATE, item0.cmdType());
        assertEquals(new BigDecimal("99.62"), item0.amount());
        assertEquals(new BigDecimal("99.60"), item0.actualAmount());
        assertEquals(new BigDecimal("0.02"), item0.totalFeeAmount());
        assertEquals(List.of("SR001", "8222900701105V2"), item0.recvNoList());
        assertEquals(LocalDate.of(2026, 11, 25), item0.separateDate());
        var item1 = page.datas().get(1);
        assertEquals(CmdType.FALLBACK, item1.cmdType());
        assertEquals(new BigDecimal("25.00"), item1.amount());
        assertEquals("FALLBACK_END", item1.status());
        assertNull(item1.totalFeeAmount());

        // Mismatched merchant_no fails closed
        assertThrows(ProtocolException.class, () -> LakalaSacsReconcileProtocol.verifyQueryResultListResponse(
                listHeaders, listBody, "app-1", "serial-1", keys.getPublic(), "OTHER_MCH"));

        // 1877 detail response verification
        byte[] detailBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_data\":{"
                + "\"total_page_num\":1,\"total_data_num\":1,\"data_num\":1,\"datas\":["
                + "{\"merchant_no\":\"8222900701105V2\",\"separate_no\":\"SEP001\",\"out_separate_no\":\"SP001\","
                + "\"merchant_name\":\"宠物平台\",\"cmd_type\":\"SEPARATE\",\"recv_no\":\"SR001\","
                + "\"amt\":\"6973\",\"log_no\":\"20261125000001\",\"log_date\":\"20261118\","
                + "\"status\":\"SUCCESS\",\"finish_date\":\"20261125\",\"request_time\":\"20261125103000\","
                + "\"actual_amt\":\"6971\",\"fee_amt\":\"2\",\"remark\":\"SO1001\"}"
                + "]}}").getBytes(StandardCharsets.UTF_8);
        var detailPage = LakalaSacsReconcileProtocol.verifyQueryDetailResponse(
                headerMap(detailBody), detailBody, "app-1", "serial-1", keys.getPublic(),
                "8222900701105V2", "SP001");
        assertEquals(1, detailPage.datas().size());
        var d = detailPage.datas().getFirst();
        assertEquals("SR001", d.recvNo());
        assertEquals(new BigDecimal("69.73"), d.amount());
        assertEquals(new BigDecimal("69.71"), d.actualAmount());
        assertEquals(new BigDecimal("0.02"), d.feeAmount());
        assertEquals(LocalDate.of(2026, 11, 18), d.logDate());
        assertEquals(LocalDate.of(2026, 11, 25), d.finishDate());
        assertEquals(LocalDateTime.of(2026, 11, 25, 10, 30, 0), d.requestTime());
    }

    @Test
    void httpClientHitsOfficialEndpointsChecksSignatureErrorsAndRejectsActiveTransaction() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>();
        byte[] listBody = ("{\"code\":\"SACS0000\",\"msg\":\"成功\",\"resp_data\":{"
                + "\"total_page_num\":0,\"total_data_num\":0,\"data_num\":0,\"datas\":[]}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpHeaders listHeaders = httpHeaders(listBody);
        LakalaSacsReconcileHttpClient sitClient = client(Environment.SIT, request -> {
            uri.set(request.uri().toString());
            assertTrue(request.headers().firstValue("Authorization").orElse("").startsWith("LKLAPI-SHA256withRSA "));
            return new WireResponse(200, listHeaders, new ByteArrayInputStream(listBody));
        });
        var verifiedList = sitClient.queryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 1, 50),
                NONCE);
        assertEquals("https://test.wsmsd.cn/sit/api/v3/sacs/queryResultList", uri.get());
        assertTrue(verifiedList.page().datas().isEmpty());
        assertTrue(verifiedList.rawResponseSha256().matches("[0-9a-f]{64}"));

        LakalaSacsReconcileHttpClient prodClient = client(Environment.PRODUCTION, request -> {
            uri.set(request.uri().toString());
            return new WireResponse(200, listHeaders, new ByteArrayInputStream(listBody));
        });
        prodClient.queryDetail(QueryDetailInput.byOutSeparateNo(AT, "M1", "SP001", 1, 50), NONCE);
        assertEquals("https://s2.lakala.com/api/v3/sacs/queryDetail", uri.get());

        // IO error -> ChannelUnknownException(IO)
        LakalaSacsReconcileHttpClient ioFail = client(Environment.SIT, req -> { throw new IOException("timeout"); });
        ChannelUnknownException ioEx = assertThrows(ChannelUnknownException.class, () -> ioFail.queryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 1, 50),
                NONCE));
        assertEquals(Reason.IO, ioEx.reason());

        // Non-200 -> HTTP_STATUS
        LakalaSacsReconcileHttpClient statusFail = client(Environment.SIT,
                req -> new WireResponse(502, listHeaders, new ByteArrayInputStream(listBody)));
        assertEquals(Reason.HTTP_STATUS, assertThrows(ChannelUnknownException.class, () -> statusFail.queryDetail(
                QueryDetailInput.byOutSeparateNo(AT, "M1", "SP001", 1, 50), NONCE)).reason());

        // Non-SACS0000 -> CHANNEL_RESPONSE
        byte[] errBody = "{\"code\":\"SACS9999\",\"msg\":\"系统异常\"}".getBytes(StandardCharsets.UTF_8);
        HttpHeaders errHeaders = httpHeaders(errBody);
        LakalaSacsReconcileHttpClient bizErr = client(Environment.SIT,
                req -> new WireResponse(200, errHeaders, new ByteArrayInputStream(errBody)));
        assertEquals(Reason.CHANNEL_RESPONSE, assertThrows(ChannelUnknownException.class, () -> bizErr.queryResultList(
                QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 1, 50),
                NONCE)).reason());

        // Rejects calls inside active DB transaction
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> sitClient.queryResultList(
                    QueryResultListInput.byDateRange(AT, "M1", LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), 1, 50),
                    NONCE));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static LakalaSacsReconcileHttpClient client(Environment env, LakalaHttpClient.Exchange exchange) {
        return new LakalaSacsReconcileHttpClient(env,
                new Credentials("app-1", "merchant-serial", keys.getPrivate(), "serial-1", keys.getPublic()),
                exchange);
    }

    private static Map<String, String> headerMap(byte[] body) throws Exception {
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
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Lklapi-Appid", "app-1");
        map.put("Lklapi-Serial", "serial-1");
        map.put("Lklapi-Timestamp", time);
        map.put("Lklapi-Nonce", nonce);
        map.put("Lklapi-Signature", sig);
        return map;
    }

    private static HttpHeaders httpHeaders(byte[] body) throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        headerMap(body).forEach((k, v) -> map.put(k, List.of(v)));
        return HttpHeaders.of(map, (k, v) -> true);
    }
}
