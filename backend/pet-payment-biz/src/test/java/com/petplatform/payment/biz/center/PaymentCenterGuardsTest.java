package com.petplatform.payment.biz.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPayment;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CreateCenterPaymentCommand;
import com.petplatform.payment.api.dto.PaymentCenterTypes.QueryCenterPayment;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.WechatPayParameters;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Offline guards of the payment center entry points: everything here is rejected before any SQL or channel
 * call (the DataSource, channel and identity mocks must stay untouched).
 */
class PaymentCenterGuardsTest {
    private static final OffsetDateTime EXPIRE = OffsetDateTime.of(2026, 11, 4, 8, 10, 0, 0, ZoneOffset.UTC);
    private final DataSource source = mock(DataSource.class);
    private final PaymentChannel channel = mock(PaymentChannel.class);
    private final PaymentIdentityApi identities = mock(PaymentIdentityApi.class);
    private final PaymentMerchantBindings bindings = (m, s) -> { throw new AssertionError("ORDER binding must not be used"); };

    private PaymentDispatchService.Settings settings() {
        return new PaymentDispatchService.Settings("OUT_ORG", "subject", "127.0.0.1", "https://notify.invalid/pay",
                ZoneId.of("UTC"), new SecretKeySpec(new byte[32], "AES"), false);
    }

    private PaymentCenterService service() {
        var receipts = new PaymentCenterReceiptWriter(source, () -> 1L, (h, b, e) -> null,
                mock(IntegrationEventPublisher.class), ZoneId.of("UTC"), "PLATFORM_MERCHANT");
        return new PaymentCenterService(source, () -> 1L, PaymentCenterBizPolicy.demoOnly(new BigDecimal("0.01")),
                bindings, identities, channel, receipts, new JdbcAsyncTaskSubmitter(source, () -> 1L), settings(), "1",
                Clock.systemUTC());
    }

    private static CommandContext user(String requestId) {
        return new CommandContext(requestId, "trace", OperatorType.USER, "7001", "TEST");
    }

    private static String code(Supplier<?> call) {
        return assertThrows(ApiException.class, call::get).code();
    }

    @Test void createRejectsBadCommandsBeforeTouchingTheDatabase() {
        var center = service();
        BigDecimal ok = new BigDecimal("0.01");
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> center.create(null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(null, "DEMO", "1", ok, EXPIRE))));
        assertEquals(CommonApiCodes.FORBIDDEN, code(() -> center.create(new CreateCenterPaymentCommand(
                new CommandContext("r1", "t", OperatorType.SYSTEM, null, "TEST"), "DEMO", "1", ok, EXPIRE))));
        assertEquals(CommonApiCodes.FORBIDDEN, code(() -> center.create(new CreateCenterPaymentCommand(
                new CommandContext("r1", "t", OperatorType.MERCHANT_STAFF, "7001", "TEST"), "DEMO", "1", ok, EXPIRE))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(user(" "), "DEMO", "1", ok, EXPIRE))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> center.create(new CreateCenterPaymentCommand(
                new CommandContext("r1", "t", OperatorType.USER, "07001", "TEST"), "DEMO", "1", ok, EXPIRE))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(user("r1"), "BOOKING_ORDER", "1", ok, EXPIRE))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(user("r1"), "DEMO", "1", new BigDecimal("0.02"), EXPIRE))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(user("r1"), "DEMO", "a b", ok, EXPIRE))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(user("r1"), "DEMO", "1", ok, EXPIRE.plusNanos(1)))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                code(() -> center.create(new CreateCenterPaymentCommand(user("r1"), "DEMO", "1", ok, null))));
        verifyNoInteractions(source, channel, identities);
    }

    @Test void createInsideAnOuterTransactionFailsClosed() {
        var center = service();
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, code(() -> center.create(new CreateCenterPaymentCommand(
                    user("r1"), "DEMO", "1", new BigDecimal("0.01"), EXPIRE))));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(source, channel, identities);
    }

    @Test void queryAllowsOnlyUserOrSystemAndValidatesTheBizKey() {
        var center = service();
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> center.query(null)));
        assertEquals(CommonApiCodes.FORBIDDEN, code(() -> center.query(new QueryCenterPayment(
                new QueryContext("t", OperatorType.PLATFORM_OPERATOR, "1"), "DEMO", "1"))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> center.query(new QueryCenterPayment(
                new QueryContext("t", OperatorType.USER, "x"), "DEMO", "1"))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> center.query(new QueryCenterPayment(
                new QueryContext("t", OperatorType.SYSTEM, null), "demo", "1"))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> center.query(new QueryCenterPayment(
                new QueryContext("t", OperatorType.SYSTEM, null), "DEMO", "a/b"))));
        verifyNoInteractions(source);
    }

    @Test void settleTypeIsValidatedAtConstruction() {
        var receipts = new PaymentCenterReceiptWriter(source, () -> 1L, (h, b, e) -> null,
                mock(IntegrationEventPublisher.class), ZoneId.of("UTC"), "PLATFORM_MERCHANT");
        for (String bad : new String[] {null, "", "2", " 1"})
            assertThrows(IllegalArgumentException.class, () -> new PaymentCenterService(source, () -> 1L,
                    PaymentCenterBizPolicy.none(), bindings, identities, channel, receipts,
                    new JdbcAsyncTaskSubmitter(source, () -> 1L), settings(), bad, Clock.systemUTC()), "settleType=" + bad);
    }

    @Test void receiptWriterRequiresAConfirmedZoneAndPlatformMerchantAndRejectsMalformedBodies() {
        assertThrows(NullPointerException.class, () -> new PaymentCenterReceiptWriter(source, () -> 1L,
                (h, b, e) -> null, mock(IntegrationEventPublisher.class), null, "M"));
        assertThrows(IllegalArgumentException.class, () -> new PaymentCenterReceiptWriter(source, () -> 1L,
                (h, b, e) -> null, mock(IntegrationEventPublisher.class), ZoneId.of("UTC"), " "));
        var writer = new PaymentCenterReceiptWriter(source, () -> 1L, (h, b, e) -> null,
                mock(IntegrationEventPublisher.class), ZoneId.of("UTC"), "PLATFORM_MERCHANT");
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> writer.receiveNotification(Map.of(), null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> writer.receiveNotification(null, new byte[] {1})));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> writer.receiveNotification(Map.of(), new byte[65_537])));
        for (String body : new String[] {"[]", "{}", "{\"out_trade_no\":1}", "{\"out_trade_no\":\"01\"}",
                "{\"out_trade_no\":\"1\",\"out_trade_no\":\"2\"}"})
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> writer.receiveNotification(Map.of(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8))), body);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, code(() -> writer.receiveQuery(1L, null)));
        verifyNoInteractions(source);
    }

    @Test void routerRejectsMalformedInputAndOuterTransactions() {
        var writer = new PaymentCenterReceiptWriter(source, () -> 1L, (h, b, e) -> null,
                mock(IntegrationEventPublisher.class), ZoneId.of("UTC"), "PLATFORM_MERCHANT");
        var router = new PaymentCallbackRouter(source, writer, mock(PaymentNotificationService.class));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> router.receive(Map.of(), new byte[0])));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> router.receive(Map.of(), "{}".getBytes())));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    code(() -> router.receive(Map.of(), "{\"out_trade_no\":\"1\"}".getBytes())));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(source);
    }

    @Test void factsRequireACallerTransactionAndATrustedActor() {
        var facts = new PaymentCenterFactsApiImpl(source);
        assertEquals(CommonApiCodes.FORBIDDEN, code(() -> facts.requireSucceeded("DEMO", "1", null)));
        assertEquals(CommonApiCodes.FORBIDDEN, code(() -> facts.requireSucceeded("DEMO", "1",
                new QueryContext("t", OperatorType.MERCHANT_STAFF, "1"))));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, code(() -> facts.requireSucceeded("DEMO", "",
                new QueryContext("t", OperatorType.SYSTEM, null))));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, code(() -> facts.requireSucceeded("DEMO", "1",
                new QueryContext("t", OperatorType.SYSTEM, null))));
        verifyNoInteractions(source);
    }

    @Test void centerPaymentNeverPrintsParameters() {
        var payment = new CenterPayment("1", "2", "DEMO", "3", "LAKALA_WECHAT",
                new WechatPayParameters("ts", "nonce", "prepay_id=secret", "RSA", "signature"), EXPIRE, false);
        assertEquals("CenterPayment[redacted]", payment.toString());
        assertTrue(!payment.wechatPayParameters().toString().contains("signature"));
    }
}
