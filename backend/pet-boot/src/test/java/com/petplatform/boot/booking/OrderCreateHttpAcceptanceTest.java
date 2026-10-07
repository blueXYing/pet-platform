package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.boot.adapter.web.c.COrderCreateController;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.apiimpl.BookingMerchantFactsApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.api.command.OrderCreationApi;
import com.petplatform.order.biz.apiimpl.OrderCreationApiImpl;
import com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderProtectionFactsApiImpl;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import com.petplatform.user.biz.apiimpl.PaymentIdentityApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Order-create/payment-initiation HTTP face acceptance (contract 10 §3.5/§3.6) over real isolated
 * MySQL: the controller only assembles, so every case drives the production kernels — the 38
 * atomic create (snapshots, ownership, capacity, durable five-tuple idempotency with 201-first/
 * 200-replay) and the 40/41 payment chain (intent binding, one payment per order, encrypted
 * short-lived WeChat parameters through a scripted channel, the contract-40 payability window).
 * Negative faces reuse the kernels' Error12 codes; the assembly switch stays default off.
 */
class OrderCreateHttpAcceptanceTest {
    private static final String USER_A = "710100";
    private static final String USER_B = "710101";
    private static final String STORE = "710302";
    private static final String SUB_APP = "wxQaSubApp01";
    private static final String MERCHANT_NO = "QA_MERCHANT_01";
    private static final String TERM_NO = "QA_TERM_01";
    private static final String OPEN_ID = "qa-order-http-openid";
    private static final String PAY_SIGN = "S".repeat(344);
    private static final Instant NOW = Instant.parse("2029-12-31T00:00:00Z");

    @Test
    void createPersistsKernelSnapshotsAndReplaysTheSameReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String requestId = UUID.randomUUID().toString();
            ResponseEntity<ApiResponse<Map<String, Object>>> first =
                    fixture.create(requestId, inStoreBody(), USER_A);
            assertEquals(HttpStatus.CREATED, first.getStatusCode());
            Map<String, Object> data = first.getBody().data();
            assertEquals("SUCCESS", first.getBody().code());
            assertEquals(5, data.size());
            assertTrue(((String) data.get("orderId")).matches("[1-9][0-9]+"));
            assertTrue(((String) data.get("orderNo")).matches("[1-9][0-9]+"));
            assertEquals("PENDING_PAYMENT", data.get("displayStatus"));
            assertEquals("128.00", data.get("payAmount"));
            assertEquals("2029-12-31T00:10:00.000Z", data.get("paymentExpireAt"));
            // The kernel wrote the whole atomic bundle: order + snapshots + durable request.
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM pet_order"));
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM order_service_snapshot"));
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM order_pet_snapshot"));
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM schedule_reservation"));
            assertEquals("SUCCEEDED", fixture.text("SELECT status FROM order_creation_request"));
            // Same key, same params: 200 with the first success receipt, nothing new written.
            ResponseEntity<ApiResponse<Map<String, Object>>> replay =
                    fixture.create(requestId, inStoreBody(), USER_A);
            assertEquals(HttpStatus.OK, replay.getStatusCode());
            assertEquals(data, replay.getBody().data());
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM pet_order"));
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM order_creation_request"));
            // Same key, different params stays a 409 per supplement 23.
            assertCode("IDEMPOTENCY_KEY_CONFLICT", () -> fixture.create(requestId,
                    inStoreBody("2030-01-01T09:00:00Z", "2030-01-01T11:00:00Z"), USER_A));
        }
    }

    @Test
    void qualificationRejectionsReuseTheKernelErrorFace() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String ownerRequest = UUID.randomUUID().toString();
            assertNotNull(fixture.create(ownerRequest, inStoreBody(), USER_A).getBody());
            // The seeded window holds one slot: a second booking (USER_B's own pet) loses it.
            assertCode("SCHEDULE_CAPACITY_EXCEEDED", () -> fixture.create(UUID.randomUUID().toString(),
                    inStoreBody("710201"), USER_B));
            // Duration must equal the service's own minutes: the kernel owns that rule.
            assertCode("SERVICE_NOT_BOOKABLE", () -> fixture.create(UUID.randomUUID().toString(),
                    inStoreBody("2030-01-01T09:00:00Z", "2030-01-01T09:30:00Z"), USER_A));
            // The pet belongs to USER_A only.
            assertCode("PET_NOT_FOUND", () -> fixture.create(UUID.randomUUID().toString(),
                    inStoreBody(), USER_B));
            // Coupons stay unimplemented (CPN not launched): a non-null id fails closed, no rows.
            assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> fixture.create(UUID.randomUUID().toString(),
                    body(Map.of("storeId", STORE, "serviceId", "710401", "petId", "710200",
                            "fulfillmentType", "IN_STORE", "appointmentStart", "2030-01-01T09:00:00Z",
                            "appointmentEnd", "2030-01-01T10:30:00Z", "couponInstanceId", "710999")),
                    USER_A));
            // A canceled account is a plain 403 on replay of its own successful order.
            fixture.db().jdbc.update("UPDATE user_account SET status='CANCELED' WHERE id=?",
                    Long.parseLong(USER_A));
            assertCode("COMMON_FORBIDDEN", () -> fixture.create(ownerRequest,
                    inStoreBody(), USER_A));
        }
    }

    @Test
    void pickupDeliveryFailsClosedUntilTheSelectionFieldsJoinThePublicContract() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // The pinned CreateOrderRequest carries no directional window ids and no service
            // address; the 38 kernel requires both for PICKUP_DELIVERY, so the honest contract
            // shape fails closed instead of guessing window ids from the availability read.
            assertCode("COMMON_INVALID_ARGUMENT", () -> fixture.create(UUID.randomUUID().toString(),
                    body(Map.of("storeId", STORE, "serviceId", "710402", "petId", "710200",
                            "fulfillmentType", "PICKUP_DELIVERY",
                            "pickupStart", "2030-01-01T09:00:00Z",
                            "returnStart", "2030-01-01T11:30:00Z")), USER_A));
            assertEquals(0L, fixture.count("SELECT COUNT(*) FROM pet_order"));
        }
    }

    @Test
    void boundaryAndMalformedRequestsArePlainFourHundreds() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // Missing session first: 401 before any body parsing on both routes.
            MockHttpServletRequest anonymous = request("/api/v1/c/orders", null);
            assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                    () -> fixture.controller.create(inStoreBody(), anonymous)).code());
            assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                    () -> fixture.controller.pay(STORE, "{\"channel\":\"WECHAT_MINI_PROGRAM\"}",
                            anonymous)).code());
            // Request id must be the terminal UUID shape.
            for (String bad : new String[] {"not-uuid", "", "123",
                    "123456781234567812345678123456781234567812345678123456781234"}) {
                MockHttpServletRequest request = request("/api/v1/c/orders", USER_A);
                request.addHeader("X-Request-Id", bad);
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> fixture.controller.create(inStoreBody(), request)).code(), bad);
            }
            // Strict body: unknown field, missing required, explicit null on a non-nullable,
            // wrong scalar type, duplicate key, trailing tokens, query string and wrong channel.
            for (String body : new String[] {
                    body(Map.of("storeId", STORE, "serviceId", "710401", "petId", "710200",
                            "fulfillmentType", "IN_STORE", "appointmentStart", "2030-01-01T09:00:00Z",
                            "appointmentEnd", "2030-01-01T10:30:00Z", "extra", "1")),
                    body(Map.of("serviceId", "710401", "petId", "710200", "fulfillmentType",
                            "IN_STORE", "appointmentStart", "2030-01-01T09:00:00Z",
                            "appointmentEnd", "2030-01-01T10:30:00Z")),
                    "{\"storeId\":\"" + STORE + "\",\"serviceId\":\"710401\",\"petId\":\"710200\","
                            + "\"fulfillmentType\":\"IN_STORE\",\"appointmentStart\":"
                            + "\"2030-01-01T09:00:00Z\",\"appointmentEnd\":\"2030-01-01T10:30:00Z\","
                            + "\"remark\":null}",
                    "{\"storeId\":" + STORE + ",\"serviceId\":\"710401\",\"petId\":\"710200\","
                            + "\"fulfillmentType\":\"IN_STORE\",\"appointmentStart\":"
                            + "\"2030-01-01T09:00:00Z\",\"appointmentEnd\":\"2030-01-01T10:30:00Z\"}",
                    "{\"storeId\":\"" + STORE + "\",\"storeId\":\"" + STORE + "\",\"serviceId\":"
                            + "\"710401\",\"petId\":\"710200\",\"fulfillmentType\":\"IN_STORE\","
                            + "\"appointmentStart\":\"2030-01-01T09:00:00Z\",\"appointmentEnd\":"
                            + "\"2030-01-01T10:30:00Z\"}",
                    inStoreBody() + " {}",
                    "{\"storeId\":\"01\",\"serviceId\":\"710401\",\"petId\":\"710200\","
                            + "\"fulfillmentType\":\"IN_STORE\",\"appointmentStart\":"
                            + "\"2030-01-01T09:00:00Z\",\"appointmentEnd\":\"2030-01-01T10:30:00Z\"}",
                    "{\"storeId\":\"9223372036854775808\",\"serviceId\":\"710401\",\"petId\":\"710200\","
                            + "\"fulfillmentType\":\"IN_STORE\",\"appointmentStart\":"
                            + "\"2030-01-01T09:00:00Z\",\"appointmentEnd\":\"2030-01-01T10:30:00Z\"}",
                    "{\"storeId\":\"" + STORE + "\",\"serviceId\":\"710401\",\"petId\":\"710200\","
                            + "\"fulfillmentType\":\"VISIT\",\"appointmentStart\":"
                            + "\"2030-01-01T09:00:00Z\",\"appointmentEnd\":\"2030-01-01T10:30:00Z\"}",
                    "{\"storeId\":\"" + STORE + "\",\"serviceId\":\"710401\",\"petId\":\"710200\","
                            + "\"fulfillmentType\":\"IN_STORE\",\"appointmentStart\":"
                            + "\"2030-01-01 09:00\",\"appointmentEnd\":\"2030-01-01T10:30:00Z\"}",
                    "[\"" + STORE + "\"]", ""}) {
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> fixture.controller.create(body,
                                terminalRequest("/api/v1/c/orders", USER_A)))
                        .code(), body);
            }
            MockHttpServletRequest withQuery = terminalRequest("/api/v1/c/orders", USER_A);
            withQuery.addParameter("page", "1");
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> fixture.controller.create(inStoreBody(), withQuery)).code());
            // Payment route: unknown/missing/wrong channel, bad path id, query string.
            for (String body : new String[] {"{\"channel\":\"ALIPAY\"}", "{\"channel\":null}",
                    "{}", "{\"channel\":\"WECHAT_MINI_PROGRAM\",\"extra\":1}",
                    "{\"channel\":\"WECHAT_MINI_PROGRAM\"} trailing"}) {
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> fixture.controller.pay(STORE, body,
                                terminalRequest("/api/v1/c/orders/1/payments", USER_A))).code(),
                        body);
            }
            for (String path : new String[] {"abc", "01", "-1", "9223372036854775808"}) {
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> fixture.controller.pay(path, "{\"channel\":\"WECHAT_MINI_PROGRAM\"}",
                                terminalRequest("/api/v1/c/orders/" + path + "/payments", USER_A)))
                        .code(), path);
            }
            MockHttpServletRequest paymentQuery =
                    terminalRequest("/api/v1/c/orders/1/payments", USER_A);
            paymentQuery.addParameter("channel", "WECHAT_MINI_PROGRAM");
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> fixture.controller.pay(STORE, "{\"channel\":\"WECHAT_MINI_PROGRAM\"}",
                            paymentQuery)).code());
        }
    }

    @Test
    void paymentInitiationReturnsParametersAndEveryReplayKeepsOnePayment() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String orderId = fixture.createOrder();
            String requestId = UUID.randomUUID().toString();
            ApiResponse<Map<String, Object>> first = fixture.pay(orderId, requestId, USER_A);
            assertEquals("SUCCESS", first.code());
            Map<String, Object> data = first.data();
            assertEquals(4, data.size());
            assertTrue(((String) data.get("paymentId")).matches("[1-9][0-9]+"));
            assertTrue(((String) data.get("paymentNo")).matches("[1-9][0-9]+"));
            assertEquals("LAKALA_WECHAT", data.get("channel"));
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) data.get("wechatPayParameters");
            assertEquals(5, parameters.size());
            assertEquals("prepay_id=qa-prepay-1", parameters.get("package"));
            assertEquals("RSA", parameters.get("signType"));
            assertEquals(PAY_SIGN, parameters.get("paySign"));
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals("PARAMETERS_READY", fixture.text(
                    "SELECT state FROM payment_dispatch pd JOIN payment_order po "
                            + "ON po.id=pd.payment_id WHERE po.order_id=?", Long.parseLong(orderId)));
            // Same key replay: the original number is never submitted twice, same parameters.
            ApiResponse<Map<String, Object>> replay = fixture.pay(orderId, requestId, USER_A);
            assertEquals(data, replay.data());
            assertEquals(1, fixture.channel.preorders.get());
            // A new key still returns the single per-order payment (contract 41), not a second one.
            ApiResponse<Map<String, Object>> again = fixture.pay(orderId,
                    UUID.randomUUID().toString(), USER_A);
            assertEquals(data, again.data());
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM payment_order"));
            // The same key bound to another order is an idempotency conflict.
            String other = fixture.createSecondOrder();
            assertCode("IDEMPOTENCY_KEY_CONFLICT", () -> fixture.pay(other, requestId, USER_A));
            // Only the owner may initiate; a foreign session is a plain 403.
            assertCode("COMMON_FORBIDDEN", () -> fixture.pay(orderId,
                    UUID.randomUUID().toString(), USER_B));
        }
    }

    @Test
    void outsideThePaymentWindowInitiationClosesWithConflictNotNewParameters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String orderId = fixture.createOrder();
            // Contract 40: the original ten-minute deadline never extends — canPay=false means
            // the initiation is a 409, the order is never reopened.
            fixture.db().jdbc.update("UPDATE pet_order SET payment_expire_at='2020-01-01 00:00:00.000' "
                    + "WHERE id=?", Long.parseLong(orderId));
            ApiException expired = assertThrows(ApiException.class,
                    () -> fixture.pay(orderId, UUID.randomUUID().toString(), USER_A));
            assertEquals(CommonApiCodes.CONFLICT, expired.code());
            assertEquals(0, fixture.channel.preorders.get());
            assertEquals(0L, fixture.count("SELECT COUNT(*) FROM payment_order"));
        }
    }

    @Test
    void absentKernelBeansFailClosedAndTheSliceStaysDefaultOff() {
        COrderCreateController bare = new COrderCreateController(empty(), empty());
        MockHttpServletRequest request = terminalRequest("/api/v1/c/orders", USER_A);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> bare.create(inStoreBody(), request)).code());
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> bare.pay(STORE, "{\"channel\":\"WECHAT_MINI_PROGRAM\"}", request)).code());
        new ApplicationContextRunner().withUserConfiguration(COrderCreateController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(COrderCreateController.class).isEmpty());
                });
    }

    /** JSON body helper: an ordered map keeps the payloads readable in failure output. */
    private static String body(Map<String, Object> fields) {
        StringBuilder json = new StringBuilder("{");
        for (var entry : fields.entrySet()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(entry.getKey()).append("\":")
                    .append('"').append(entry.getValue()).append('"');
        }
        return json.append('}').toString();
    }

    private static String inStoreBody() {
        return inStoreBody("2030-01-01T09:00:00Z", "2030-01-01T10:30:00Z");
    }

    /** USER_A's pet (710200) is the default; USER_B books with its own pet (710201). */
    private static String inStoreBody(String petId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("storeId", STORE);
        fields.put("serviceId", "710401");
        fields.put("petId", petId);
        fields.put("fulfillmentType", "IN_STORE");
        fields.put("appointmentStart", "2030-01-01T09:00:00Z");
        fields.put("appointmentEnd", "2030-01-01T10:30:00Z");
        return body(fields);
    }

    private static String inStoreBody(String start, String end) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("storeId", STORE);
        fields.put("serviceId", "710401");
        fields.put("petId", "710200");
        fields.put("fulfillmentType", "IN_STORE");
        fields.put("appointmentStart", start);
        fields.put("appointmentEnd", end);
        return body(fields);
    }

    /** No default X-Request-Id here: every caller owns the header it wants observed. */
    private static MockHttpServletRequest request(String uri, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (userId != null) {
            request.setAttribute(CBearerSessionFilter.VIEW,
                    new MiniSessionView("session-1", userId, Instant.now(), "138****0000", "ACTIVE"));
        }
        return request;
    }

    private static MockHttpServletRequest terminalRequest(String uri, String userId) {
        MockHttpServletRequest request = request(uri, userId);
        request.addHeader("X-Request-Id", UUID.randomUUID().toString());
        return request;
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable action) {
        ApiException failure = assertThrows(ApiException.class, action);
        assertEquals(expected, failure.code());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new UnsupportedOperationException(); }
            @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    /** Real MySQL plus both kernels; only the external channel is scripted. */
    private static final class Fixture implements AutoCloseable {
        private static final AtomicLong IDS = new AtomicLong(8_800_000_000_000_000L);

        private final PaymentFoundationAcceptanceTest.Fixture foundation;
        final COrderCreateController controller;
        final ScriptedChannel channel = new ScriptedChannel();


        Fixture() throws Exception {
            foundation = new PaymentFoundationAcceptanceTest.Fixture(
                    Clock.fixed(NOW, ZoneOffset.UTC));
            try {
                db().jdbc.update("INSERT INTO user_auth_identity"
                                + " (id,user_id,identity_type,app_id,open_id,created_at,updated_at)"
                                + " VALUES (?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        8_810_100L, Long.parseLong(USER_A), "WECHAT_MINI", SUB_APP, OPEN_ID);
                // The seeded GENERAL window holds one slot only; a second same-day order needs
                // its own OPEN window (adjacent half-open, staff covers the whole 08:00-14:00 day).
                db().jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                                + "service_id,start_at,end_at,configured_capacity,status,window_kind,"
                                + "created_at,updated_at) VALUES(?,?,?,?,?,?,1,'OPEN','GENERAL',"
                                + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        8_810_500L, 710301L, 710302L, 710401L,
                        "2030-01-01 11:00:00", "2030-01-01 14:00:00");
                var source = foundation.db.source;
                byte[] rawKey = new byte[32];
                new SecureRandom().nextBytes(rawKey);
                var guard = new ScheduleCapacityGuardApiImpl(source);
                var scheduleFacts = new ScheduleProtectionFactsApiImpl(source, guard);
                var staffFacts = new MerchantCurrentStaffFactsApiImpl(source, guard);
                var orderFacts = new OrderProtectionFactsApiImpl(source, guard, scheduleFacts,
                        staffFacts, Clock.fixed(NOW, ZoneOffset.UTC));
                var proof = new ScheduleCapacityProofApiImpl(source, guard, scheduleFacts,
                        staffFacts, orderFacts, Clock.fixed(NOW, ZoneOffset.UTC), 10_000);
                var hold = new ReservationHoldApiImpl(source, IDS::incrementAndGet, guard,
                        scheduleFacts, proof, orderFacts, Clock.fixed(NOW, ZoneOffset.UTC));
                OrderCreationApi creation = new OrderCreationApiImpl(source,
                        IDS::incrementAndGet,
                        new BookingUserFactsApiImpl(source, guard),
                        new BookingMerchantFactsApiImpl(source, guard,
                                new PersistentApplicationReviewFactsReader(source,
                                        IDS::incrementAndGet)),
                        new BookingServiceFactsApiImpl(source, guard), guard, hold,
                        new BookingCreateAcceptanceTest.InputProtector(), null,
                        Clock.fixed(NOW, ZoneOffset.UTC));
                var paymentFacts = new OrderPaymentFactsApiImpl(source, guard);
                PaymentDispatchService dispatch = new PaymentDispatchService(source,
                        IDS::incrementAndGet, guard, paymentFacts,
                        new PaymentIdentityApiImpl(source, guard), foundation.preparation, channel,
                        foundation.notification, new PaymentDispatchService.Settings("OP123",
                                "Pet service", "127.0.0.1", "https://example.test/payment-notify",
                                ZoneId.of("Asia/Shanghai"),
                                new SecretKeySpec(rawKey, "AES"), false),
                        Clock.systemUTC());
                controller = new COrderCreateController(provider(creation), provider(dispatch));
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        private BookingCreateAcceptanceTest.Database db() { return foundation.db; }

        @Override public void close() { foundation.close(); }

        /** One IN_STORE order through the HTTP face itself (09:00 slot of the seeded window). */
        String createOrder() {
            return create(UUID.randomUUID().toString(), inStoreBody(), USER_A).getBody().data()
                    .get("orderId").toString();
        }

        /** A second order on the same day uses the 12:00 slot of the extra seeded window. */
        String createSecondOrder() {
            return create(UUID.randomUUID().toString(),
                    inStoreBody("2030-01-01T12:00:00Z", "2030-01-01T13:30:00Z"), USER_A).getBody()
                    .data().get("orderId").toString();
        }

        ResponseEntity<ApiResponse<Map<String, Object>>> create(
                String requestId, String body, String userId) {
            MockHttpServletRequest request = request("/api/v1/c/orders", userId);
            request.addHeader("X-Request-Id", requestId);
            return controller.create(body, request);
        }

        ApiResponse<Map<String, Object>> pay(String orderId, String requestId, String userId) {
            MockHttpServletRequest request =
                    request("/api/v1/c/orders/" + orderId + "/payments", userId);
            request.addHeader("X-Request-Id", requestId);
            return controller.pay(orderId, "{\"channel\":\"WECHAT_MINI_PROGRAM\"}", request);
        }

        String text(String sql) { return db().jdbc.queryForObject(sql, String.class); }

        String text(String sql, Object arg) {
            return db().jdbc.queryForObject(sql, String.class, arg);
        }

        long count(String sql) { return db().jdbc.queryForObject(sql, Long.class); }
    }

    /** The 41 semantics need a verified preorder; only the transport is fake here. */
    private static final class ScriptedChannel implements PaymentChannel {
        final AtomicInteger preorders = new AtomicInteger();

        @Override public VerifiedPreorder submitPreorder(PreorderInput input, RequestNonce nonce) {
            preorders.incrementAndGet();
            return new VerifiedPreorder(new PreorderResult(input.merchantNo(), input.outTradeNo(),
                    "qa-channel-trade-1", input.subAppId(), "qa-prepay-1", PAY_SIGN,
                    nonce.timestampSeconds(), nonce.nonce(), "prepay_id=qa-prepay-1", "RSA"),
                    "a".repeat(64));
        }

        @Override public VerifiedQuery lookup(QueryInput input, ExpectedPayment expected,
                RequestNonce nonce) {
            throw new IllegalStateException("QA: no query expected in this slice");
        }

        @Override public VerifiedClose requestClose(CloseInput input, RequestNonce nonce) {
            throw new IllegalStateException("QA: no close expected in this slice");
        }
    }
}
