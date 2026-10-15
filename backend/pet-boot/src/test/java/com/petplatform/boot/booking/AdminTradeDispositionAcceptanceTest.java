package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.*;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableCommand;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableReceipt;
import com.petplatform.merchant.biz.application.AdminDispositionAuthorizationPort;
import com.petplatform.merchant.biz.application.AdminMerchantDispositionService;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseCommand;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseReceipt;
import com.petplatform.order.biz.apiimpl.AdminOrderRefundProjectionConsumer;
import com.petplatform.order.biz.apiimpl.OrderAdminCloseFactsApiImpl;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionCommand;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionReceipt;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.refund.biz.application.AdminRefundDispositionService;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Check;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.RefundAuthorizationPort;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.refund.biz.application.RefundExecutionService;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.biz.apiimpl.ReservationRefundReleaseApiImpl;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Contract-63 v0.2 disposal face over the real kernel chain (user ruling 2026-10-15, doc 33):
 * the operator abnormal close walks the EXISTING order state machine and creates the full refund
 * through the ADMIN_ABNORMAL_CLOSE source; channel-query/retry re-arm the existing durable task
 * family; merchant disable reuses the existing status vocabulary. Audit rows bind the terminal
 * requestId; replays return the stored receipt and different payloads conflict.
 */
class AdminTradeDispositionAcceptanceTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-01T00:00:00.000Z");
    private static final AtomicLong SEQ = new AtomicLong(8_990_000_000_000_000L);
    static long id() { return SEQ.incrementAndGet(); }

    static final class AllowAll implements RefundAuthorizationPort {
        @Override public Decision check(Check check) { return new Decision(true, CHECKED, "1:1", "ALLOWED"); }
    }

    static final class DenyAll implements RefundAuthorizationPort {
        @Override public Decision check(Check check) { return new Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED"); }
    }

    @Test void abnormalCloseWalksExistingMachineCreatesFullRefundAndReplays() throws Exception {
        try (var f = new F()) {
            String o = f.paid();
            var command = f.closeCommand(o, "MERCHANT_UNREACHABLE");
            AbnormalCloseReceipt first = f.orders.abnormalClose(command);
            assertEquals("CANCELED", first.orderStage());
            assertEquals("ADMIN_ABNORMAL_CLOSE", first.cancelReason());
            // Same receipt on replay (same terminal requestId); one refund/audit/log row only.
            assertEquals(first, f.orders.abnormalClose(command));
            assertEquals("CANCELED", f.f.f.text("SELECT order_stage FROM pet_order"));
            assertEquals("ADMIN_ABNORMAL_CLOSE", f.f.f.text("SELECT cancel_reason FROM pet_order"));
            assertEquals("ADMIN_ABNORMAL_CLOSE", f.f.f.text("SELECT source_type FROM refund_order"));
            assertEquals("OPS", f.f.f.text("SELECT initiator_type FROM refund_order"));
            assertEquals(0, new BigDecimal("128.00").compareTo(
                f.f.f.db.jdbc.queryForObject("SELECT refund_amount FROM refund_order", BigDecimal.class)));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM order_admin_action"));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='ORDER_ADMIN_ABNORMAL_CLOSED' AND operator_type='PLATFORM'"));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM async_task WHERE task_type='ADMIN_REFUND_SUBMIT'"));
            assertEquals(0, f.f.f.db.jdbc.queryForObject("SELECT refunded_amount FROM pet_order", BigDecimal.class).signum());
            // A refund already exists: any fresh close request conflicts.
            MerchantOrderAcceptanceTest.code("ORDER_REFUND_ALREADY_CREATED",
                () -> f.orders.abnormalClose(f.closeCommand(o, "OTHER")));
            // Hard rules stay intact: the verification window is untouched by the close.
            assertEquals("UNVERIFIED", f.f.f.text("SELECT verification_status FROM pet_order"));
            // Driven through the existing worker machinery the refund completes and the
            // ORDER_ADMIN_REFUND projection releases the reservation in the same commit.
            assertTrue(f.executor(false).execute(first.refundOrderId(), "710302", false, "qa").done());
            f.adminProjection.consume(f.event("RefundSucceededEvent.v1"));
            f.adminProjection.consume(f.event("RefundSucceededEvent.v1"));
            assertEquals("RELEASED", f.f.f.text("SELECT status FROM schedule_reservation"));
            assertEquals(0, new BigDecimal("128").compareTo(
                f.f.f.db.jdbc.queryForObject("SELECT refunded_amount FROM pet_order", BigDecimal.class)));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM order_admin_action"));
        }
    }

    @Test void abnormalCloseRefusesFulfilledBlockedAndClosedOrders() throws Exception {
        for (String change : List.of(
                "UPDATE pet_order SET current_aftersale_id=88",
                "UPDATE pet_order SET current_refund_application_id=77",
                "UPDATE payment_order SET status='RECONCILIATION_REQUIRED'")) {
            try (var f = new F()) {
                String o = f.paid();
                f.f.f.db.jdbc.update(change);
                MerchantOrderAcceptanceTest.code("ORDER_STATE_NOT_ALLOWED",
                    () -> f.orders.abnormalClose(f.closeCommand(o, "STORE_OFFLINE")));
                assertEquals(0, f.f.f.count("SELECT COUNT(*) FROM order_admin_action"));
                assertEquals(0, f.f.f.count("SELECT COUNT(*) FROM refund_order"));
            }
        }
        try (var f = new F()) {
            String o = f.paid();
            f.orders.abnormalClose(f.closeCommand(o, "OTHER"));
            // A closed order left the closable stage set.
            MerchantOrderAcceptanceTest.code("ORDER_STATE_NOT_ALLOWED",
                () -> f.orders.abnormalClose(f.closeCommand(o, "OTHER")));
        }
    }

    @Test void dispositionAuthorizationAndIdempotencyConflictsAreEnforced() throws Exception {
        try (var f = new F()) {
            String o = f.paid();
            var denied = new com.petplatform.order.biz.application.AdminOrderDispositionService(
                f.f.f.db.source, AdminTradeDispositionAcceptanceTest::id, f.f.f.guard,
                check -> new com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision(
                    false, CHECKED, "1:1", "ACTION_NOT_GRANTED"),
                f.paid, f.refundPresence, f.refunds);
            MerchantOrderAcceptanceTest.code(CommonApiCodes.FORBIDDEN,
                () -> denied.abnormalClose(f.closeCommand(o, "OTHER")));
            assertEquals(0, f.f.f.count("SELECT COUNT(*) FROM order_admin_action"));
            assertEquals("PENDING_CONFIRM", f.f.f.text("SELECT order_stage FROM pet_order"));
        }
        try (var f = new F()) {
            String o = f.paid();
            var command = f.closeCommand(o, "OTHER");
            var receipt = f.orders.abnormalClose(command);
            // Same requestId, different payload: conflict, no second refund.
            MerchantOrderAcceptanceTest.code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, () -> f.orders.abnormalClose(
                new AbnormalCloseCommand(o, "OTHER", "a completely different reason text",
                    command.context(), command.authorization())));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(Long.parseLong(receipt.refundOrderId()),
                f.f.f.db.jdbc.queryForObject("SELECT refund_order_id FROM pet_order", Long.class));
            // Unknown order reads as 404 (anti-enumeration).
            MerchantOrderAcceptanceTest.code(CommonApiCodes.NOT_FOUND,
                () -> f.orders.abnormalClose(f.closeCommand("922337203685477580", "OTHER")));
        }
    }

    @Test void channelQueryAndRetryReArmExistingTasksWithAuditReplay() throws Exception {
        try (var f = new F()) {
            String o = f.paid();
            var close = f.orders.abnormalClose(f.closeCommand(o, "MERCHANT_UNREACHABLE"));
            // A CREATED refund has no channel fact to query yet.
            MerchantOrderAcceptanceTest.code(CommonApiCodes.CONFLICT,
                () -> f.refundActions.channelQuery(f.queryCommand(close.refundOrderId())));
            // The submit-family trigger re-arms the exact existing task and writes the audit row.
            DispositionReceipt retry = f.refundActions.retry(f.queryCommand(close.refundOrderId()));
            assertEquals("READY", retry.taskState());
            assertEquals("ADMIN_REFUND_SUBMIT:" + close.refundOrderId() + ":0", retry.taskKey());
            assertEquals(retry, f.refundActions.retry(f.queryCommand(close.refundOrderId())));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM refund_admin_action WHERE action_type='RETRY'"));
            // Same requestId bound to a different operator identity conflicts.
            var retryCommand = f.queryCommand(close.refundOrderId());
            f.refundActions.retry(retryCommand);
            MerchantOrderAcceptanceTest.code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                () -> f.refundActions.retry(new DispositionCommand(close.refundOrderId(),
                    retryCommand.context(), new AdminAuthorization("9007199254740994", 1))));
            // Drive the refund to SUCCESS through the existing worker machinery.
            assertTrue(f.executor(false).execute(close.refundOrderId(), "710302", false, "qa").done());
            assertEquals("SUCCESS", f.f.f.text("SELECT status FROM refund_order"));
            MerchantOrderAcceptanceTest.code(CommonApiCodes.CONFLICT,
                () -> f.refundActions.retry(f.queryCommand(close.refundOrderId())));
            MerchantOrderAcceptanceTest.code(CommonApiCodes.CONFLICT,
                () -> f.refundActions.channelQuery(f.queryCommand(close.refundOrderId())));
            // Denial never writes the audit row.
            var deniedActions = new AdminRefundDispositionService(f.f.f.db.source,
                AdminTradeDispositionAcceptanceTest::id, f.refunds, new DenyAll());
            MerchantOrderAcceptanceTest.code(CommonApiCodes.FORBIDDEN,
                () -> deniedActions.retry(f.queryCommand(close.refundOrderId())));
            assertEquals(2, f.f.f.count("SELECT COUNT(*) FROM refund_admin_action"));
        }
    }

    @Test void merchantDisableReusesStatusVocabularyAndReplaysSafely() throws Exception {
        try (var f = new F()) {
            // An in-flight order exists BEFORE the disable (SSOT §15: it must not block it).
            String o = f.paid();
            var command = f.disableCommand("710301", "merchant unreachable after repeated contact");
            DisableReceipt first = f.merchants.disable(command);
            assertEquals("OFFLINE", first.status());
            assertEquals(first, f.merchants.disable(command));
            assertEquals("OFFLINE", f.f.f.text("SELECT status FROM merchant WHERE id=710301"));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM merchant_governance_action WHERE action_type='DISABLE'"));
            // Already offline: a fresh requestId conflicts instead of double-writing.
            MerchantOrderAcceptanceTest.code(CommonApiCodes.CONFLICT,
                () -> f.merchants.disable(f.disableCommand("710301", "a different reason for the audit")));
            assertEquals(1, f.f.f.count("SELECT COUNT(*) FROM merchant_governance_action"));
            // The in-flight order keeps operating for the OFFLINE merchant (SSOT §15).
            assertNotNull(f.merchantService.decide(f.rejectCommand(o)).refundOrderId());
            // Unknown merchant reads as 404 (anti-enumeration).
            MerchantOrderAcceptanceTest.code(CommonApiCodes.NOT_FOUND,
                () -> f.merchants.disable(f.disableCommand("922337203685477580", "merchant unreachable after repeated contact")));
        }
    }

    static final class F implements AutoCloseable {
        final MerchantOrderAcceptanceTest.F f;
        final PaymentSuccessFactsApiImpl paid;
        final RefundOrderFactsApiImpl refundPresence;
        final OrderAdminCloseFactsApiImpl adminCloseFacts;
        final LateRefundService refunds;
        final com.petplatform.order.biz.application.AdminOrderDispositionService orders;
        final AdminRefundDispositionService refundActions;
        final AdminMerchantDispositionService merchants;
        final AdminOrderRefundProjectionConsumer adminProjection;
        final com.petplatform.order.biz.application.MerchantOrderService merchantService;

        F() throws Exception {
            f = new MerchantOrderAcceptanceTest.F();
            paid = f.paid;
            refundPresence = new RefundOrderFactsApiImpl(f.f.db.source, f.f.guard);
            adminCloseFacts = new OrderAdminCloseFactsApiImpl(f.f.db.source, f.f.guard);
            refunds = new LateRefundService(f.f.db.source,
                AdminTradeDispositionAcceptanceTest::id, f.f.guard,
                new com.petplatform.order.biz.apiimpl.OrderLatePaymentFactsApiImpl(f.f.db.source, f.f.guard, f.f.reservationExpiry),
                paid, f.f.publisher, f.facts, true, null, null, null, null, null, adminCloseFacts);
            orders = new com.petplatform.order.biz.application.AdminOrderDispositionService(f.f.db.source,
                AdminTradeDispositionAcceptanceTest::id, f.f.guard,
                check -> new com.petplatform.order.biz.application.AdminTradeAuthorizationPort.Decision(
                    true, CHECKED, "1:1", "ALLOWED"),
                paid, refundPresence, refunds);
            refundActions = new AdminRefundDispositionService(f.f.db.source,
                AdminTradeDispositionAcceptanceTest::id, refunds, new AllowAll());
            merchants = new AdminMerchantDispositionService(f.f.db.source,
                AdminTradeDispositionAcceptanceTest::id,
                check -> new AdminDispositionAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED"));
            adminProjection = new AdminOrderRefundProjectionConsumer(f.f.db.source,
                AdminTradeDispositionAcceptanceTest::id, f.f.guard, adminCloseFacts, refunds,
                new ReservationRefundReleaseApiImpl(f.f.db.source,
                    AdminTradeDispositionAcceptanceTest::id, f.f.guard, refunds));
            merchantService = f.service;
        }

        AbnormalCloseCommand closeCommand(String o, String reason) {
            return new AbnormalCloseCommand(o, reason, "operator decision text for qa", new CommandContext(
                UUID.randomUUID().toString(), "trade-qa", OperatorType.PLATFORM_OPERATOR, "9007199254740993", "ADMIN_WEB"),
                new com.petplatform.order.api.command.AdminOrderDispositionApi.AdminAuthorization("9007199254740993", 1));
        }

        DispositionCommand queryCommand(String refundId) {
            return new DispositionCommand(refundId, new CommandContext(
                UUID.randomUUID().toString(), "trade-qa", OperatorType.PLATFORM_OPERATOR, "9007199254740993", "ADMIN_WEB"),
                new AdminAuthorization("9007199254740993", 1));
        }

        DisableCommand disableCommand(String merchantId, String reason) {
            return new DisableCommand(merchantId, reason, new CommandContext(
                UUID.randomUUID().toString(), "trade-qa", OperatorType.PLATFORM_OPERATOR, "9007199254740993", "ADMIN_WEB"),
                new com.petplatform.merchant.api.command.AdminMerchantDispositionApi.AdminAuthorization("9007199254740993", 1));
        }

        com.petplatform.order.api.command.MerchantOrderCommandApi.Command rejectCommand(String o) {
            return new com.petplatform.order.api.command.MerchantOrderCommandApi.Command(new CommandContext(
                UUID.randomUUID().toString(), "merchant-qa", OperatorType.USER, "710300", "MINIAPP"), o, 0,
                "REJECT", "OTHER", "staff cannot attend", null);
        }

        String paid() throws Exception { return f.paid(); }

        RefundExecutionService executor(boolean unknown) { return f.executor(unknown); }

        com.petplatform.event.api.DispatchedEvent event(String type) { return f.event(type); }

        public void close() { f.close(); }
    }
}
