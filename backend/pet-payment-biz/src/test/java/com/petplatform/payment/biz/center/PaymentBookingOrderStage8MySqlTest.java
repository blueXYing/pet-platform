package com.petplatform.payment.biz.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import com.petplatform.payment.api.dto.PaymentCenterTypes.QueryCenterPayment;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.InitiatedPayment;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparePaymentCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.BindReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAttachmentRef;
import com.petplatform.payment.api.dto.ReceiverTypes.UpsertReceiverCommand;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportQuery;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawCommand;
import com.petplatform.payment.biz.apiimpl.PaymentPreparationApiImpl;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentParameterCipher;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Result;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedFallbackSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedQueryAmt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSeparateSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedApplyBind;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedReceiverAction;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedReceiverDetail;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedRelationApplyPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedUploadFile;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ReceiverActionResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ReceiverDetailResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.RelationApplyPageResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileResult;
import com.petplatform.payment.biz.settlement.DbSettlementReceiverLookup;
import com.petplatform.payment.biz.settlement.ReceiverAccountService;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider;
import com.petplatform.payment.biz.settlement.ReceiverChannel;
import com.petplatform.payment.biz.settlement.ReceiverSensitiveCipher;
import com.petplatform.payment.biz.settlement.SettlementAdminService;
import com.petplatform.payment.biz.settlement.SettlementChannel;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementEventConsumers;
import com.petplatform.payment.biz.settlement.SettlementExecutionService;
import com.petplatform.payment.biz.settlement.SettlementOrderBlockerPort;
import com.petplatform.payment.biz.settlement.SettlementReceiptWriter;
import com.petplatform.payment.biz.settlement.SubsidyReportService;
import com.petplatform.payment.biz.settlement.WalletChannel;
import com.petplatform.payment.biz.settlement.WalletReceiptWriter;
import com.petplatform.payment.biz.settlement.WalletService;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.api.query.BookingUserFactsApi;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCR-PAY-CENTER-001 Stage 8 end-to-end closed-loop real MySQL 8 integration test across all 10 schemas
 * (06/13/40/41/75/78/79/80/81/82):
 * <ul>
 *   <li>ORDER preparation stamps unified {@code (biz_type='BOOKING_ORDER', biz_no=orderId)} + {@code settle_type='1'};</li>
 *   <li>Deduplicated {@link PaymentParameterCipher} seals &amp; replays parameters while preserving ORDER vs Center AAD isolation;</li>
 *   <li>{@link PaymentCallbackRouter} routes both Stage 8 {@code BOOKING_ORDER} and legacy {@code biz_type IS NULL} ORDER callbacks
 *       to {@link PaymentNotificationService}, while {@link PaymentCenterReceiptWriter} still fails closed on ORDER rows;</li>
 *   <li>Unified {@link PaymentCenterService#query} &amp; {@link PaymentCenterFactsApiImpl#requireSucceeded} read {@code BOOKING_ORDER} rows;</li>
 *   <li>Full V1 money loop: Pay -> Receiver ACTIVE (Stage 4) -> Verify -> Split SACS (Stage 2) -> Subsidy Report (Stage 6) -> LAEP D1 Withdraw (Stage 5);</li>
 *   <li>{@link SettlementCompensationScanner} invokes both Stage 4 {@code scanStuckBindApplies} and Stage 5 {@code scanStuckWithdrawals} hooks.</li>
 * </ul>
 */
class PaymentBookingOrderStage8MySqlTest {
    private static final String PLATFORM_MCH = "82229005943096D";
    private static final String TERM_NO = "TERM001";
    private static final String SUB_APP = "wxPetStage8";
    private static final String ORG_CODE = "1950836";
    private static final String RECEIVER_NO = "SR202611258888";
    private static final String LOG_NO = "66210306998888";
    private static final LocalDate LOG_DATE = LocalDate.of(2026, 11, 25);
    private static final String USER_ID = "7001";
    private static final String OTHER_USER_ID = "7002";
    private static final String MERCHANT_ID = "5001";
    private static final String STORE_ID = "6001";
    private static final String ORDER_ID = "8001";
    private static final String SHA = "a".repeat(64);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final ScheduleCapacityGuardApi NO_GUARD = new ScheduleCapacityGuardApi() {
        @Override public void acquire(List<String> storeIds, QueryContext context) {}
        @Override public void requireHeld(String storeId, DataSource callerSource) {}
    };

    private static KeyPair notifyKeys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        notifyKeys = gen.generateKeyPair();
    }

    @Test
    void fullClosedLoopBookingOrderPaymentSettlementReceiverWalletSubsidy() throws Exception {
        try (Db db = Db.open()) {
            Instant baseNow = Instant.parse("2026-11-25T04:00:00Z");
            Clock clock = Clock.fixed(baseNow, ZoneOffset.UTC);

            byte[] paramKeyBytes = new byte[32];
            new SecureRandom().nextBytes(paramKeyBytes);
            var paramKey = new SecretKeySpec(paramKeyBytes, "AES");
            var dispatchSettings = new PaymentDispatchService.Settings(
                    ORG_CODE, "Pet Bath Service", "127.0.0.1",
                    "https://api.example.com/api/v1/callbacks/lakala/pay",
                    ZoneOffset.UTC, paramKey, false);

            var publisher = new TransactionalOutboxPublisher(db.source, db.ids, new ObjectMapper());
            PaymentMerchantBindings bindings = new PaymentMerchantBindings() {
                @Override public Binding require(String merchantId, String storeId) {
                    return new Binding(PLATFORM_MCH, TERM_NO, SUB_APP);
                }
                @Override public Binding requirePlatform() {
                    return new Binding(PLATFORM_MCH, TERM_NO, SUB_APP);
                }
            };
            PaymentIdentityApi identities = new PaymentIdentityApi() {
                @Override public PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
                        String storeId, QueryContext context) {
                    return new PaymentIdentity(appId, "openid-" + userId);
                }
                @Override public PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
                        QueryContext context) {
                    return new PaymentIdentity(appId, "openid-" + userId);
                }
            };

            // 1) ORDER Payment Preparation + Dispatch (Stage 8 BOOKING_ORDER + deduplicated PaymentParameterCipher)
            BigDecimal payAmount = new BigDecimal("80.00"); // 100.00 service - 20.00 platform coupon
            OrderPaymentFactsApi orderPaymentFacts = mock(OrderPaymentFactsApi.class);
            when(orderPaymentFacts.locateStore(eq(ORDER_ID), any())).thenReturn(STORE_ID);
            when(orderPaymentFacts.readForPayment(eq(ORDER_ID), eq(STORE_ID), any())).thenReturn(
                    new OrderPaymentFact(ORDER_ID, USER_ID, MERCHANT_ID, STORE_ID, "9001",
                            "PENDING_PAYMENT", "UNPAID", "NONE", payAmount, BigDecimal.ZERO,
                            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10).truncatedTo(ChronoUnit.MILLIS)));

            var preparation = new PaymentPreparationApiImpl(db.source, db.ids, NO_GUARD,
                    orderPaymentFacts, mock(BookingUserFactsApi.class), bindings, "1");
            AtomicReference<PaymentReceiptVerifier.VerifiedNotice> verifiedNotice = new AtomicReference<>();
            var orderNotifications = new PaymentNotificationService(db.source, db.ids, NO_GUARD,
                    (h, b, e) -> verifiedNotice.get(), publisher, ZoneOffset.UTC);
            var payChannel = new StubPaymentChannel();
            var dispatch = new PaymentDispatchService(db.source, db.ids, NO_GUARD, orderPaymentFacts,
                    identities, preparation, payChannel, orderNotifications, dispatchSettings, clock);

            var payCmd = new PreparePaymentCommand(
                    new CommandContext("11111111-1111-4111-8111-111111111111", "tr-pay",
                            OperatorType.USER, USER_ID, "MINIAPP"),
                    ORDER_ID);
            InitiatedPayment initiated = dispatch.create(payCmd);
            InitiatedPayment replayed = dispatch.create(payCmd);
            assertEquals(initiated.paymentId(), replayed.paymentId());
            assertEquals(initiated.wechatPayParameters(), replayed.wechatPayParameters());
            assertEquals(1, payChannel.preorders.get());
            assertEquals("1", payChannel.lastSettleType.get());
            assertEquals("BOOKING_ORDER", db.text("SELECT biz_type FROM payment_order WHERE id=" + initiated.paymentId()));
            assertEquals(ORDER_ID, db.text("SELECT biz_no FROM payment_order WHERE id=" + initiated.paymentId()));
            assertEquals("1", db.text("SELECT settle_type FROM payment_order WHERE id=" + initiated.paymentId()));

            // Verify ORDER vs Center parameter cipher AAD domain isolation after deduplication.
            var orderCipher = new PaymentParameterCipher(paramKey, PaymentParameterCipher.ORDER_AAD_DOMAIN,
                    PaymentParameterCipher.ORDER_IDENTITY_DOMAIN, "order unavailable");
            var centerCipher = new CenterParameterCipher(paramKey);
            var sealedOrder = orderCipher.encrypt(101L, 202L, initiated.wechatPayParameters());
            assertThrows(ApiException.class, () -> centerCipher.decrypt(101L, 202L, sealedOrder.ciphertext(), sealedOrder.iv()));

            // 2) PaymentCallbackRouter routes BOOKING_ORDER -> PaymentNotificationService; Center writer rejects ORDER row.
            var centerReceipts = new PaymentCenterReceiptWriter(db.source, db.ids,
                    (h, b, e) -> verifiedNotice.get(), publisher, ZoneOffset.UTC, PLATFORM_MCH);
            var centerService = new PaymentCenterService(db.source, db.ids, PaymentCenterBizPolicy.none(),
                    bindings, identities, payChannel, centerReceipts,
                    new JdbcAsyncTaskSubmitter(db.source, db.ids), dispatchSettings, "1", clock);
            var router = new PaymentCallbackRouter(db.source, centerReceipts, orderNotifications);

            byte[] payCallbackBody = ("{\"out_trade_no\":\"" + initiated.paymentNo() + "\"}").getBytes(StandardCharsets.UTF_8);
            verifiedNotice.set(new PaymentReceiptVerifier.VerifiedNotice(
                    PLATFORM_MCH, initiated.paymentNo(), "TRADE_" + initiated.paymentNo(), "SUCCESS",
                    payAmount, payAmount, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10).truncatedTo(ChronoUnit.SECONDS),
                    "WECHAT", LOG_NO, LOG_DATE));

            // Pure center receipt writer must still fail closed on ORDER rows.
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> centerReceipts.receiveNotification(Map.of(), payCallbackBody)).code());

            // Router sends BOOKING_ORDER row to PaymentNotificationService -> PAID + PaymentSucceededEvent.v1.
            var payReceipt = router.receive(Map.of(), payCallbackBody);
            assertTrue(payReceipt.paid());
            assertFalse(payReceipt.replayed());
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + initiated.paymentId()));
            assertEquals(LOG_NO, db.text("SELECT log_no FROM payment_order WHERE id=" + initiated.paymentId()));
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));

            // Also verify legacy pre-Stage-8 ORDER row (biz_type IS NULL) still routes cleanly, and hybrid row fails closed.
            db.exec("INSERT INTO payment_order(id,payment_no,order_id,biz_type,biz_no,store_id,merchant_id,user_id,"
                    + "amount,status,channel,expire_at,merchant_no,term_no,sub_appid,currency,dispatch_state,created_at,updated_at) "
                    + "VALUES(99001,99002,99003,NULL,NULL,6001,5001,7001,50.00,'INIT','LAKALA_WECHAT',"
                    + "UTC_TIMESTAMP(3)+INTERVAL 10 MINUTE,'" + PLATFORM_MCH + "','" + TERM_NO + "','" + SUB_APP + "','CNY','PREPARED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            verifiedNotice.set(new PaymentReceiptVerifier.VerifiedNotice(
                    PLATFORM_MCH, "99002", "TRADE_99002", "SUCCESS",
                    new BigDecimal("50.00"), new BigDecimal("50.00"),
                    LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10).truncatedTo(ChronoUnit.SECONDS),
                    "WECHAT", "66210306990002", LOG_DATE));
            assertTrue(router.receive(Map.of(), "{\"out_trade_no\":\"99002\"}".getBytes(StandardCharsets.UTF_8)).paid());

            db.exec("INSERT INTO payment_order(id,payment_no,order_id,biz_type,biz_no,store_id,merchant_id,user_id,"
                    + "amount,status,channel,expire_at,merchant_no,term_no,sub_appid,currency,dispatch_state,created_at,updated_at) "
                    + "VALUES(99011,99012,99013,'DEMO','BAD_HYBRID',6001,5001,7001,10.00,'INIT','LAKALA_WECHAT',"
                    + "UTC_TIMESTAMP(3)+INTERVAL 10 MINUTE,'" + PLATFORM_MCH + "','" + TERM_NO + "','" + SUB_APP + "','CNY','PREPARED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> router.receive(Map.of(), "{\"out_trade_no\":\"99012\"}".getBytes(StandardCharsets.UTF_8))).code());

            // 3) Unified PaymentCenterService.query & PaymentCenterFactsApiImpl.requireSucceeded read BOOKING_ORDER!
            var queriedByUser = centerService.query(new QueryCenterPayment(
                    new QueryContext("tr-q1", OperatorType.USER, USER_ID),
                    PaymentCenterBizPolicy.BOOKING_ORDER, ORDER_ID));
            assertEquals(initiated.paymentId(), queriedByUser.paymentId());
            assertEquals("PAID", queriedByUser.status());
            assertEquals(payAmount, queriedByUser.paidAmount());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class, () -> centerService.query(
                    new QueryCenterPayment(new QueryContext("tr-q2", OperatorType.USER, OTHER_USER_ID),
                            PaymentCenterBizPolicy.BOOKING_ORDER, ORDER_ID))).code());

            var centerFacts = new PaymentCenterFactsApiImpl(db.source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.source));
            var successFact = tx.execute(st -> centerFacts.requireSucceeded(
                    PaymentCenterBizPolicy.BOOKING_ORDER, ORDER_ID,
                    new QueryContext("tr-f1", OperatorType.SYSTEM, null)));
            assertNotNull(successFact);
            assertEquals(initiated.paymentId(), successFact.paymentId());
            assertEquals(payAmount, successFact.paidAmount());

            // 4) Stage 4 Receiver Onboarding -> ACTIVE -> live DbSettlementReceiverLookup
            var receiverService = db.receiverService(new StubReceiverChannel(), clock);
            var receiverLookup = new DbSettlementReceiverLookup(db.source);
            var adminCtx = new CommandContext("22222222-2222-4222-8222-222222222222", "tr-r1",
                    OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN");
            var createdReceiver = receiverService.upsertOrCreate(new UpsertReceiverCommand(
                    adminCtx, MERCHANT_ID, true, "Stage8 Grooming", "PERSONAL",
                    "6222021234567894837", "张三", "58", "17", "310101199001011234",
                    "102100000030", "ICBC", "102100000030", "13812345678",
                    null, null, null, null, null,
                    List.of(new ReceiverAttachmentRef("ID_CARD_FRONT", "asset-id-f"),
                            new ReceiverAttachmentRef("ID_CARD_BEHIND", "asset-id-b"),
                            new ReceiverAttachmentRef("BANK_CARD", "asset-bank")),
                    "stage8 onboarding"));
            assertEquals("RECEIVER_CREATED", createdReceiver.status());
            var boundReceiver = receiverService.bind(new BindReceiverCommand(
                    new CommandContext("33333333-3333-4333-8333-333333333333", "tr-r2",
                            OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    MERCHANT_ID, "asset-coop", "bind"));
            assertEquals("BIND_APPLYING", boundReceiver.status());
            byte[] bindCb = ("{\"respData\":{\"optType\":\"ADD\",\"applyId\":\"" + boundReceiver.applyId()
                    + "\",\"merCupNo\":\"" + PLATFORM_MCH + "\",\"receiverNo\":\"" + RECEIVER_NO
                    + "\",\"auditStatus\":\"1\",\"auditStatusText\":\"审核通过\",\"remark\":\"ok\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            var activeReceiver = receiverService.receiveBindNotification(sign1009(bindCb), bindCb);
            assertEquals("ACTIVE", activeReceiver.status());
            assertTrue(receiverLookup.findActiveByMerchantId(Long.parseLong(MERCHANT_ID)).isPresent());

            // 5) Stage 2 Settlement: OnPaymentSucceeded -> OnOrderVerified -> Execute -> 1297 Callback -> SPLIT_SUCCEEDED
            var settlementAdmin = new SettlementAdminService(db.source, db.ids, new JdbcAsyncTaskSubmitter(db.source, db.ids));
            OrderSettlementFactsApi orderSettlementFacts = (oId, ctx) -> new OrderSettlementFact(
                    ORDER_ID, MERCHANT_ID, STORE_ID,
                    false, true,
                    new BigDecimal("100.00"), BigDecimal.ZERO,
                    new BigDecimal("20.00"), payAmount, BigDecimal.ZERO,
                    null, null, null,
                    OffsetDateTime.ofInstant(baseNow, ZoneOffset.UTC),
                    BigDecimal.ZERO); // merchantCouponDiscount (contract 79): platform coupon only
            var claims = new JdbcOutboxConsumeGuard(db.source, db.ids);
            var onPaid = new SettlementEventConsumers.OnPaymentSucceeded(db.source, claims, orderSettlementFacts, settlementAdmin);
            var onVerified = new SettlementEventConsumers.OnOrderVerified(db.source, claims, orderSettlementFacts, settlementAdmin);

            DispatchedEvent paidEvent = db.eventByAggregate("PaymentSucceededEvent.v1", Long.parseLong(initiated.paymentId()));
            onPaid.consume(paidEvent);
            onVerified.consume(new DispatchedEvent("910001", "OrderVerifiedEvent.v2", 2,
                    OffsetDateTime.ofInstant(baseNow, ZoneOffset.UTC), "ORDER", Long.parseLong(ORDER_ID), "tr-ver",
                    "{\"orderId\":\"" + ORDER_ID + "\",\"verifiedAt\":\"2026-11-25T04:00:00Z\"}"));

            long settlementOrderId = db.longVal("SELECT id FROM settlement_order WHERE order_id=" + ORDER_ID);
            assertEquals("WAITING", db.text("SELECT status FROM settlement_order WHERE id=" + settlementOrderId));
            // Math check: 100.00 * (1 - 0.006) = 99.40 merchantShare; split & subsidy finalize after 2396 query in executeDue.
            assertEquals(new BigDecimal("99.40"), db.decimal("SELECT merchant_share FROM settlement_order WHERE id=" + settlementOrderId));

            // Move eligible_at into the past to execute T+7 due split.
            db.exec("UPDATE settlement_order SET eligible_at='2026-11-25 03:59:00.000' WHERE id=" + settlementOrderId);
            var sacsChannel = new StubSettlementChannel(8000L);
            var settlementExec = new SettlementExecutionService(db.source, db.ids, sacsChannel, receiverLookup,
                    SettlementOrderBlockerPort.none(),
                    new JdbcAsyncTaskSubmitter(db.source, db.ids),
                    new SettlementExecutionService.Settings(PLATFORM_MCH, SHANGHAI, BigDecimal.ZERO,
                            ORG_CODE, "https://api.example.com/api/v1/callbacks/lakala/separate"),
                    clock);
            var settlementReceipts = new SettlementReceiptWriter(db.source, db.ids,
                    SettlementReceiptWriter.NotificationVerifier.fromPublicKey(notifyKeys.getPublic()), publisher);

            settlementExec.executeDue(settlementOrderId, 1);
            assertEquals(new BigDecimal("80.00"), db.decimal("SELECT split_amount FROM settlement_order WHERE id=" + settlementOrderId));
            assertEquals(new BigDecimal("19.40"), db.decimal("SELECT platform_subsidy_due FROM settlement_order WHERE id=" + settlementOrderId));
            String outSeparateNo = db.text("SELECT out_separate_no FROM settlement_instruction WHERE settlement_order_id=" + settlementOrderId);
            byte[] splitCb = ("{\"separate_no\":\"SEP_" + outSeparateNo + "\",\"out_separate_no\":\"" + outSeparateNo
                    + "\",\"cmd_type\":\"SEPARATE\",\"log_no\":\"" + LOG_NO + "\",\"log_date\":\"20261125\","
                    + "\"total_amt\":\"8000\",\"status\":\"SUCCESS\",\"final_status\":\"SUCCESS\","
                    + "\"detail_datas\":[{\"recv_no\":\"" + RECEIVER_NO + "\",\"amt\":\"8000\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            settlementReceipts.receiveNotification(sign1009(splitCb), splitCb);
            assertEquals("SPLIT_SUCCEEDED", db.text("SELECT status FROM settlement_order WHERE id=" + settlementOrderId));
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='SettlementSplitSucceededEvent.v1'"));

            // 6) Stage 6 Read-Only Subsidy Report reports exact 19.40 platform subsidy payable.
            var subsidyService = new SubsidyReportService(db.source);
            var subsidyResp = subsidyService.querySubsidyReport(new SubsidyReportQuery(
                    new QueryContext("tr-sub", OperatorType.PLATFORM_OPERATOR, "1001"),
                    MERCHANT_ID, null, null, 1, 20));
            assertEquals(1L, subsidyResp.summary().totalOrderCount());
            assertEquals(new BigDecimal("99.40"), subsidyResp.summary().totalMerchantShare());
            assertEquals(new BigDecimal("80.00"), subsidyResp.summary().totalSplitAmount());
            assertEquals(new BigDecimal("19.40"), subsidyResp.summary().totalSubsidyDue());

            // 7) Stage 5 Merchant Wallet (1269 live balance, zero subsidy leaked) + D1 Withdraw (1319 + 1272 -> SUCCEEDED)
            var walletChannel = new StubWalletChannel(new BigDecimal("80.00"));
            var walletReceipts = new WalletReceiptWriter(db.source, db.ids,
                    WalletReceiptWriter.NotificationVerifier.fromPublicKey(notifyKeys.getPublic()), publisher, SHANGHAI);
            var walletService = new WalletService(db.source, db.ids, receiverLookup, walletChannel, walletReceipts,
                    new JdbcAsyncTaskSubmitter(db.source, db.ids),
                    new WalletService.Settings(ORG_CODE, "https://api.example.com/api/v1/callbacks/lakala/withdrawals"),
                    clock);

            var walletSummary = walletService.getMerchantWallet(MERCHANT_ID,
                    new QueryContext("tr-w1", OperatorType.MERCHANT_STAFF, "7100"));
            assertEquals(new BigDecimal("80.00"), walletSummary.availableBalance());
            assertEquals(new BigDecimal("0.00"), walletSummary.pendingSettlementAmount());
            assertTrue(walletSummary.receiverActive());
            assertTrue(walletSummary.channelBalanceAvailable());

            var withdrawal = walletService.withdraw(new WithdrawCommand(
                    new CommandContext("44444444-4444-4444-8444-444444444444", "tr-w2",
                            OperatorType.MERCHANT_STAFF, "7100", "MINIAPP"),
                    MERCHANT_ID, new BigDecimal("80.00")));
            assertEquals("PROCESSING", withdrawal.status());
            byte[] withdrawCb = ("{\"drawJnl\":\"" + withdrawal.drawJnl() + "\",\"merOrderNo\":\"" + withdrawal.merOrderNo()
                    + "\",\"mercId\":\"" + RECEIVER_NO + "\",\"drawAmt\":\"80.00\",\"drawFee\":\"0.00\","
                    + "\"drawMode\":\"D1\",\"batchAutoSettle\":\"01\",\"drawState\":\"DRAW.SUCCESS\","
                    + "\"settleNo\":\"STL_S8\",\"completeTime\":\"2026-11-25 12:05:00\",\"memo\":\"交易成功\"}")
                    .getBytes(StandardCharsets.UTF_8);
            walletReceipts.applyNotification(sign1009(withdrawCb), withdrawCb);
            assertEquals("SUCCEEDED", db.text("SELECT status FROM withdrawal_order WHERE id=" + withdrawal.withdrawalId()));
            assertEquals(1, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='WithdrawalSucceededEvent.v1'"));
        }
    }

    @Test
    void compensationScannerRecoversStuckReceiverBindAndWithdrawalHooks() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-25T10:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);

            var receiverService = db.receiverService(new StubReceiverChannel(), clock);
            var publisher = new TransactionalOutboxPublisher(db.source, db.ids, new ObjectMapper());
            var walletReceipts = new WalletReceiptWriter(db.source, db.ids,
                    WalletReceiptWriter.NotificationVerifier.fromPublicKey(notifyKeys.getPublic()), publisher, SHANGHAI);
            var walletService = new WalletService(db.source, db.ids, new DbSettlementReceiverLookup(db.source),
                    new StubWalletChannel(new BigDecimal("100.00")), walletReceipts,
                    new JdbcAsyncTaskSubmitter(db.source, db.ids),
                    new WalletService.Settings(ORG_CODE, "https://api.example.com/api/v1/callbacks/lakala/withdrawals"),
                    clock);

            // Seed a stuck BIND_APPLYING receiver (updated 10 min ago, missing RECEIVER_BIND_QUERY task).
            db.exec("INSERT INTO settlement_receiver_account(id,merchant_id,receiver_name,receiver_type,acct_name,"
                    + "acct_type_code,acct_certificate_type,acct_open_bank_code,acct_open_bank_name,acct_clear_bank_code,"
                    + "acct_no_cipher,acct_cert_no_cipher,contact_mobile_cipher,"
                    + "acct_no_masked,acct_no_last4,acct_cert_no_masked,contact_mobile_masked,"
                    + "status,receiver_no,apply_id,settle_type,last_synced_at,version,created_at,updated_at) "
                    + "VALUES(88001,5099,'Stuck Shop','PERSONAL','李四','58','17','102100000030','ICBC','102100000030',"
                    + "'c1','c2','c3','6222****4837','4837','3101****1234','138****5678',"
                    + "'BIND_APPLYING','SR_STUCK_99','APPLY-STUCK-1','01','2026-11-25 09:50:00.000',0,"
                    + "'2026-11-25 09:40:00.000','2026-11-25 09:50:00.000')");

            // Seed a stuck PROCESSING withdrawal (updated 10 min ago, missing WALLET_WITHDRAW_QUERY task).
            db.exec("INSERT INTO withdrawal_order(id,mer_order_no,merchant_id,receiver_no,amount,status,draw_jnl,"
                    + "request_key,request_fingerprint,requested_by_user_id,requested_at,sent_at,version,created_at,updated_at) "
                    + "VALUES(88002,'WDSTUCK0001',5099,'SR_STUCK_99',50.00,'PROCESSING','JNL_STUCK_1',"
                    + "X'010203','" + SHA + "',7100,'2026-11-25 09:45:00.000','2026-11-25 09:50:00.000',0,"
                    + "'2026-11-25 09:45:00.000','2026-11-25 09:50:00.000')");

            try (var scanner = new SettlementCompensationScanner(db.source, db.ids, clock,
                    List.of(receiverService::scanStuckBindApplies, walletService::scanStuckWithdrawals))) {
                assertEquals(2, scanner.scanOnce());
                assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_key='RECEIVER_BIND_QUERY:5099:APPLY-STUCK-1' AND status='READY'"));
                assertEquals(1, db.count("SELECT COUNT(*) FROM async_task WHERE task_key='WALLET_WITHDRAW_QUERY:88002' AND status='READY'"));
                // Second pass is a pure no-op because both tasks are now alive.
                assertEquals(0, scanner.scanOnce());
            }
        }
    }

    // ---- Helpers & Fakes -------------------------------------------------------------------

    private static Map<String, String> sign1009(byte[] body) throws Exception {
        String ts = "1764045000", nonce = "stage8nonce1";
        byte[] prefix = (ts + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[prefix.length + body.length + 1];
        System.arraycopy(prefix, 0, msg, 0, prefix.length);
        System.arraycopy(body, 0, msg, prefix.length, body.length);
        msg[msg.length - 1] = '\n';
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(notifyKeys.getPrivate());
        sig.update(msg);
        return Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"" + ts
                + "\", nonce_str=\"" + nonce + "\", signature=\""
                + Base64.getEncoder().encodeToString(sig.sign()) + "\"");
    }

    private static final class StubPaymentChannel implements PaymentChannel {
        final AtomicInteger preorders = new AtomicInteger();
        final AtomicReference<String> lastSettleType = new AtomicReference<>();

        @Override
        public VerifiedPreorder submitPreorder(LakalaProtocol.PreorderInput input, RequestNonce nonce) {
            preorders.incrementAndGet();
            lastSettleType.set(input.settleType());
            return new VerifiedPreorder(new LakalaProtocol.PreorderResult(
                    input.merchantNo(), input.outTradeNo(), null, input.subAppId(),
                    "wx-prepay-stage8", "sig-stage8", "1764043200", "nonceS8",
                    "prepay_id=wx-prepay-stage8", "RSA"), SHA);
        }

        @Override
        public VerifiedQuery lookup(LakalaProtocol.QueryInput input, LakalaProtocol.ExpectedPayment expected,
                RequestNonce nonce) {
            throw new AssertionError("unexpected lookup");
        }

        @Override
        public VerifiedClose requestClose(LakalaProtocol.CloseInput input, RequestNonce nonce) {
            throw new AssertionError("unexpected close");
        }
    }

    private static final class StubReceiverChannel implements ReceiverChannel {
        @Override
        public VerifiedUploadFile uploadFile(UploadFileInput input) {
            return new VerifiedUploadFile(new UploadFileResult(
                    input.orgCode(), input.orderNo(),
                    "G1/M00/06/64/" + input.attType() + "." + input.attExtName(),
                    input.attType()), SHA);
        }

        @Override
        public VerifiedReceiverAction applyLedgerReceiver(ApplyReceiverInput input) {
            return new VerifiedReceiverAction(new ReceiverActionResult(
                    input.orderNo(), input.orgCode(), input.orgCode(), "Stage8 Org", RECEIVER_NO), SHA);
        }

        @Override
        public VerifiedReceiverAction modifyLedgerReceiver(ModifyReceiverInput input) {
            return new VerifiedReceiverAction(new ReceiverActionResult(
                    input.orderNo(), input.orgCode(), input.orgCode(), "Stage8 Org", input.receiverNo()), SHA);
        }

        @Override
        public VerifiedReceiverDetail queryReceiverDetail(QueryReceiverInput input) {
            return new VerifiedReceiverDetail(new ReceiverDetailResult(
                    input.receiverNo(), "Stage8 Grooming", "138****5678", null, null, null, null, null,
                    "6222****4837", "张三", "58", "17", null, "102100000030", "ICBC",
                    "102100000030", "OPEN"), SHA);
        }

        @Override
        public VerifiedApplyBind applyBind(ApplyBindInput input) {
            return new VerifiedApplyBind(new ApplyBindResult(
                    input.orderNo(), input.orgCode(), "548099616395188888"), SHA);
        }

        @Override
        public VerifiedRelationApplyPage listRelationApply(ListRelationApplyInput input) {
            return new VerifiedRelationApplyPage(new RelationApplyPageResult(0, 1, 10, 0, List.of()), SHA);
        }
    }

    private static final class StubSettlementChannel implements SettlementChannel {
        private final long canSeparateCents;

        StubSettlementChannel(long canSeparateCents) {
            this.canSeparateCents = canSeparateCents;
        }

        @Override
        public VerifiedQueryAmt querySeparableAmount(QueryAmtInput input) {
            return new VerifiedQueryAmt(new QueryAmtResult(
                    input.merchantNo(), input.logNo(), input.logDate(), canSeparateCents, canSeparateCents), SHA);
        }

        @Override
        public VerifiedSeparateSubmit submitSeparate(SeparateInput input) {
            return new VerifiedSeparateSubmit(new SeparateSubmitResult(
                    input.outSeparateNo(), "SEP_" + input.outSeparateNo(), SplitStatus.PROCESSING,
                    "PROCESSING", input.logNo(), input.logDate(), input.totalAmountCents()), SHA);
        }

        @Override
        public VerifiedFallbackSubmit submitFallback(FallbackInput input) {
            return new VerifiedFallbackSubmit(new FallbackSubmitResult(
                    input.outSeparateNo(), "FB_" + input.outSeparateNo(),
                    input.originSeparateNo(), input.originOutSeparateNo(),
                    input.totalAmountCents(), SplitStatus.PROCESSING, "PROCESSING"), SHA);
        }

        @Override
        public VerifiedSplitOutcome querySeparateOutcome(QueryPlusInput input, ExpectedSplit expected) {
            throw new AssertionError("unexpected split query");
        }
    }

    private static final class StubWalletChannel implements WalletChannel {
        private final BigDecimal available;

        StubWalletChannel(BigDecimal available) {
            this.available = available;
        }

        @Override
        public VerifiedBalance queryBalance(BalanceQueryInput input) {
            return new VerifiedBalance(new BalanceResult("PAY_S8", "04", "NORMAL",
                    available, available, BigDecimal.ZERO), SHA);
        }

        @Override
        public VerifiedWithdrawSubmit withdrawD1(WithdrawD1Input input) {
            return new VerifiedWithdrawSubmit(new WithdrawD1Result("JNL_STAGE8_1", input.merOrderNo()), SHA);
        }

        @Override
        public VerifiedWithdrawOutcome queryWithdrawal(WithdrawQueryInput input) {
            throw new AssertionError("unexpected withdrawal query");
        }
    }

    private static final class Db implements AutoCloseable {
        private static final List<String> SCHEMAS = List.of(
                "06-核心数据库Schema-v0.1.sql",
                "13-Async-Infra-Schema-v0.1.sql",
                "40-Payment-Foundation-Schema-v0.1.sql",
                "41-Payment-Dispatch-Schema-v0.1.sql",
                "75-Payment-Center-Schema-v0.1.sql",
                "78-Payment-Settlement-Schema-v0.1.sql",
                "79-Payment-Settlement-Fallback-Schema-v0.1.sql",
                "80-Payment-Receiver-Schema-v0.1.sql",
                "81-Payment-Wallet-Schema-v0.1.sql",
                "82-Payment-Reconcile-Schema-v0.1.sql",
                "83-Payment-Merchant-Acquiring-Schema-v0.1.sql");

        final String name;
        final String baseUrl;
        final String user;
        final String password;
        final DataSource source;
        final AtomicLong seq = new AtomicLong(850_000_000L);
        final SnowflakeIdGenerator ids = seq::incrementAndGet;

        private Db(String name, String baseUrl, String user, String password, DataSource source) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.user = user;
            this.password = password;
            this.source = source;
        }

        static Db open() throws Exception {
            String url = System.getenv("PAYMENT_CENTER_MYSQL_URL");
            if (url == null || url.isBlank()) url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "MySQL URL env not set");
            String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
            String pass = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", "root"));
            String dbName = "paystage8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String normalized = url.endsWith("/") ? url : url + "/";

            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(normalized + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(pass);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }

            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(normalized + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&allowMultiQueries=true");
            ds.setUser(user);
            ds.setPassword(pass);

            Path root = repoRoot().resolve("docs/03-database");
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                for (String file : SCHEMAS) {
                    ScriptUtils.executeSqlScript(c, new EncodedResource(
                            new FileSystemResource(root.resolve(file)), StandardCharsets.UTF_8));
                }
                s.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            }
            return new Db(dbName, normalized, user, pass, ds);
        }

        ReceiverAccountService receiverService(ReceiverChannel channel, Clock clock) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            var cipher = new ReceiverSensitiveCipher(new SecretKeySpec(key, "AES"));
            ReceiverAttachmentProvider assets = assetId -> new ReceiverAttachmentProvider.AssetContent(
                    assetId, assetId.contains("coop") ? "pdf" : "jpg",
                    assetId + (assetId.contains("coop") ? ".pdf" : ".jpg"),
                    new byte[] {1, 2, 3, 4});
            return new ReceiverAccountService(source, ids, cipher, assets, channel,
                    new JdbcAsyncTaskSubmitter(source, ids),
                    ReceiverAccountService.BindNotificationVerifier.fromPublicKey(notifyKeys.getPublic()),
                    new ReceiverAccountService.Settings(ORG_CODE, PLATFORM_MCH, null,
                            "https://api.example.com/api/v1/callbacks/lakala/ledger"),
                    clock);
        }

        void exec(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        int count(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        }

        long longVal(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }

        String text(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getString(1);
            }
        }

        BigDecimal decimal(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }

        DispatchedEvent eventByAggregate(String eventType, long aggregateId) throws Exception {
            String sql = "SELECT event_id,event_type,event_version,occurred_at,aggregate_type,aggregate_id,"
                    + "trace_id,CAST(payload AS CHAR) AS payload FROM integration_event_outbox "
                    + "WHERE event_type=? AND aggregate_id=?";
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, eventType);
                ps.setLong(2, aggregateId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "outbox event absent: " + eventType);
                    return new DispatchedEvent(
                            rs.getString("event_id"),
                            rs.getString("event_type"),
                            rs.getInt("event_version"),
                            rs.getObject("occurred_at", LocalDateTime.class).atOffset(ZoneOffset.UTC),
                            rs.getString("aggregate_type"),
                            rs.getLong("aggregate_id"),
                            rs.getString("trace_id"),
                            rs.getString("payload"));
                }
            }
        }

        @Override
        public void close() throws Exception {
            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(baseUrl + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(password);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }

        private static String env(String key, String fallback) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static Path repoRoot() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return java.util.Objects.requireNonNull(p);
        }
    }
}
