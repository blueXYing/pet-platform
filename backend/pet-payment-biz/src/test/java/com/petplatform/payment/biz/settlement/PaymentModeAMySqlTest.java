package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileRunSummary;
import com.petplatform.payment.api.dto.SettlementTypes.MarkSettlementVerifiedCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RegisterSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.ResumeSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementConfigView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementInstructionView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.api.dto.SettlementTypes.UpsertSettlementConfigCommand;
import com.petplatform.payment.api.dto.WalletTypes.MerchantWalletView;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawCommand;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.SettlementRefundGate.GateDecision;
import com.petplatform.payment.biz.application.SettlementRefundGate.RefundGateRequest;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.BalanceResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.DrawState;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Input;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawD1Result;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawQueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedFallbackSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedQueryAmt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSeparateSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ReceiverShare;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateSubmitResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitDetail;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.DetailItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.ResultListItem;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.payment.biz.task.WalletQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Real MySQL 8 integration tests for Mode A (Ruling 40 §10 / CCR-PAY-CENTER-001 §14):
 * per-merchant acquiring bindings, reverse split to platform receiver, Mode A fallback & subsidy
 * recomputation, payType="01" merchant wallet, and multi-merchant daily reconciliation.
 */
class PaymentModeAMySqlTest {
    private static final String PLATFORM_MCH = "822651053990MW8";
    private static final String PLATFORM_TERM = "Q6426723";
    private static final String PLATFORM_APP = "wxc12540a8f32b372c";
    private static final String PLATFORM_RECEIVER = "SR20261126PLAT01";
    private static final String MERCHANT_1_MCH = "82266507295001Z";
    private static final String MERCHANT_1_TERM = "T9500101";
    private static final String MERCHANT_2_MCH = "82266507295002Z";
    private static final String MERCHANT_2_TERM = "T9500202";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void merchantAcquiringConfigAuditAndFailClosedBindings() throws Exception {
        try (Db db = Db.open()) {
            var admin = db.adminService(PLATFORM_RECEIVER);
            var bindings = new DbMerchantPaymentBindings(db.source,
                    new PaymentMerchantBindings.Binding(PLATFORM_MCH, PLATFORM_TERM, PLATFORM_APP));

            // Unconfigured merchant fails closed; NEVER falls back to platform merchant.
            ApiException missing = assertThrows(ApiException.class, () -> bindings.require("710301", "710302"));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, missing.code());
            assertTrue(bindings.findAcquiringMerchantNo(710301L).isEmpty());
            assertEquals(PLATFORM_MCH, bindings.requirePlatform().merchantNo());

            // Partial pair rejected.
            CommandContext ctxBad = new CommandContext("req-bad-1", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            ApiException unpaired = assertThrows(ApiException.class, () -> admin.upsertConfig(
                    new UpsertSettlementConfigCommand(ctxBad, "710301", 7, new BigDecimal("0.6000"), true,
                            MERCHANT_1_MCH, null, "partial")));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, unpaired.code());

            // Valid paired save persists config + audit and activates DbMerchantPaymentBindings.
            CommandContext ctx1 = new CommandContext("req-save-1", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            SettlementConfigView saved = admin.upsertConfig(new UpsertSettlementConfigCommand(
                    ctx1, "710301", 7, new BigDecimal("0.6000"), true,
                    MERCHANT_1_MCH, MERCHANT_1_TERM, "Mode A onboard"));
            assertEquals(MERCHANT_1_MCH, saved.lakalaMerchantNo());
            assertEquals(MERCHANT_1_TERM, saved.lakalaTermNo());
            assertEquals(1, db.count("SELECT COUNT(*) FROM settlement_config_audit WHERE merchant_id=710301 "
                    + "AND after_lakala_merchant_no='" + MERCHANT_1_MCH + "' "
                    + "AND after_lakala_term_no='" + MERCHANT_1_TERM + "'"));

            // Replay under same requestId succeeds; altered fields conflict.
            SettlementConfigView replayed = admin.upsertConfig(new UpsertSettlementConfigCommand(
                    ctx1, "710301", 7, new BigDecimal("0.6000"), true,
                    MERCHANT_1_MCH, MERCHANT_1_TERM, "Mode A onboard"));
            assertEquals(saved.version(), replayed.version());
            ApiException conflict = assertThrows(ApiException.class, () -> admin.upsertConfig(
                    new UpsertSettlementConfigCommand(ctx1, "710301", 7, new BigDecimal("0.6000"), true,
                            MERCHANT_2_MCH, MERCHANT_2_TERM, "conflict")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());

            PaymentMerchantBindings.Binding resolved = bindings.require("710301", "710302");
            assertEquals(MERCHANT_1_MCH, resolved.merchantNo());
            assertEquals(MERCHANT_1_TERM, resolved.termNo());
            assertEquals(PLATFORM_APP, resolved.subAppId());
            assertEquals(Optional.of(MERCHANT_1_MCH), bindings.findAcquiringMerchantNo(710301L));

            // Disabling settlement config causes fail-closed immediately.
            CommandContext ctxDisable = new CommandContext("req-dis-1", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN");
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    ctxDisable, "710301", 7, new BigDecimal("0.6000"), false,
                    MERCHANT_1_MCH, MERCHANT_1_TERM, "suspend"));
            assertThrows(ApiException.class, () -> bindings.require("710301", "710302"));
            assertTrue(bindings.findAcquiringMerchantNo(710301L).isEmpty());
        }
    }

    @Test
    void modeASplitHoldWhenPlatformReceiverMissingThenSelfPlusPlatformAndZeroFeeSelfOnly() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-26T10:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var admin = db.adminService(PLATFORM_RECEIVER);
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("cfg-1", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    "710301", 7, new BigDecimal("0.6000"), true, MERCHANT_1_MCH, MERCHANT_1_TERM, "init"));

            // 1. ¥100.00 order acquired by MERCHANT_1_MCH (no merchant SR... receiver!).
            db.insertPaidPayment(10001L, 710301L, MERCHANT_1_MCH, new BigDecimal("100.00"),
                    "LOG10001", LocalDate.of(2026, 11, 18));
            SettlementOrderView so1 = registerAndVerify(admin, "10001", "20001", "710301",
                    new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                    OffsetDateTime.parse("2026-11-18T10:00:00Z"));

            // Without platformReceiverNo, positive 0.60 platform fee holds ON_HOLD before sending 1293.
            var channel = new RecordingSacsChannel();
            channel.canSeparateCents = 10000L;
            var execWithoutPlatRecv = db.executionService(channel, null, clock);
            SettlementDueTask.Outcome heldOutcome = execWithoutPlatRecv.executeDue(Long.parseLong(so1.settlementOrderId()), 1);
            assertTrue(heldOutcome instanceof SettlementDueTask.Outcome.Held h && "PLATFORM_RECEIVER_NOT_CONFIGURED".equals(h.holdReason()));
            assertEquals(0, channel.separateInputs.size());

            // Resume once platformReceiverNo is configured -> 1293 sends self (9940) + platform SR (60).
            admin.resumeHeldOrder(new ResumeSettlementCommand(
                    new CommandContext("res-1", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    so1.settlementOrderId(), "configured platform receiver"));
            var execWithPlatRecv = db.executionService(channel, PLATFORM_RECEIVER, clock);
            assertTrue(execWithPlatRecv.executeDue(Long.parseLong(so1.settlementOrderId()), 2)
                    instanceof SettlementDueTask.Outcome.Done);

            assertEquals(1, channel.separateInputs.size());
            SeparateInput sent = channel.separateInputs.getFirst();
            assertEquals(MERCHANT_1_MCH, sent.merchantNo());
            assertEquals(10000L, sent.totalAmountCents());
            assertEquals(List.of(
                    ReceiverShare.toSelf(MERCHANT_1_MCH, 9940L),
                    ReceiverShare.toReceiver(PLATFORM_RECEIVER, 60L)), sent.recvDatas());

            // 1296 query confirms SUCCESS with self actual_amt=9935, fee=5 -> writer parses recv_merchant_no detail!
            SettlementInstructionView instr = admin.listInstructions(so1.settlementOrderId(), sysQuery()).getFirst();
            assertEquals(MERCHANT_1_MCH, instr.merchantNo());
            assertEquals(PLATFORM_RECEIVER, instr.receiverNo());
            channel.nextOutcome.set(new SplitOutcome(
                    instr.separateNo(), instr.outSeparateNo(), CmdType.SEPARATE,
                    "LOG10001", LocalDate.of(2026, 11, 18), "0", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26),
                    10000L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 9995L, 5L, null,
                    List.of(
                            new SplitDetail(MERCHANT_1_MCH, null, 9940L, 9935L, 5L, null),
                            new SplitDetail(null, PLATFORM_RECEIVER, 60L, 60L, 0L, null))));
            var writer = db.receiptWriter(PLATFORM_RECEIVER);
            var queryCoord = new SettlementQueryCoordinator(db.source, channel, writer, SHANGHAI, clock);
            assertEquals(SettlementQueryTask.Outcome.DONE, queryCoord.execute(Long.parseLong(instr.instructionId())));

            SettlementOrderView settled = admin.getById(so1.settlementOrderId(), sysQuery());
            assertEquals("SPLIT_SUCCEEDED", settled.status());
            assertEquals(new BigDecimal("99.35"), settled.actualMerchantAmount());
            assertEquals(new BigDecimal("0.05"), settled.channelSplitFee());

            // 2. Zero-fee merchant (feeRate=0.0000%) ¥0.01 test order with platformReceiverNo=null:
            //    merchantShare=0.01, platformRetained=0.00 -> self-only split SUCCEEDS without SR_PLATFORM!
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("cfg-zero", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    "710302", 7, new BigDecimal("0.0000"), true, MERCHANT_2_MCH, MERCHANT_2_TERM, "zero-fee"));
            db.insertPaidPayment(10002L, 710302L, MERCHANT_2_MCH, new BigDecimal("0.01"),
                    "LOG10002", LocalDate.of(2026, 11, 18));
            SettlementOrderView so2 = registerAndVerify(admin, "10002", "20002", "710302",
                    new BigDecimal("0.01"), BigDecimal.ZERO, new BigDecimal("0.01"),
                    OffsetDateTime.parse("2026-11-18T10:00:00Z"));
            channel.canSeparateCents = 1L;
            assertTrue(execWithoutPlatRecv.executeDue(Long.parseLong(so2.settlementOrderId()), 1)
                    instanceof SettlementDueTask.Outcome.Done);
            SeparateInput pennySent = channel.separateInputs.get(1);
            assertEquals(List.of(ReceiverShare.toSelf(MERCHANT_2_MCH, 1L)), pennySent.recvDatas());
            SettlementInstructionView pennyInstr = admin.listInstructions(so2.settlementOrderId(), sysQuery()).getFirst();
            assertNull(pennyInstr.receiverNo());
            channel.nextOutcome.set(new SplitOutcome(
                    pennyInstr.separateNo(), pennyInstr.outSeparateNo(), CmdType.SEPARATE,
                    "LOG10002", LocalDate.of(2026, 11, 18), "0", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26),
                    1L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 1L, 0L, null,
                    List.of(new SplitDetail(MERCHANT_2_MCH, null, 1L, 1L, 0L, null))));
            assertEquals(SettlementQueryTask.Outcome.DONE, queryCoord.execute(Long.parseLong(pennyInstr.instructionId())));
            assertEquals("SPLIT_SUCCEEDED", admin.getById(so2.settlementOrderId(), sysQuery()).status());

            // 3. 82-prefix platform merchant number used directly as platformReceiverNo (recv_no="822651053990MW8").
            db.insertPaidPayment(10003L, 710301L, MERCHANT_1_MCH, new BigDecimal("100.00"),
                    "LOG10003", LocalDate.of(2026, 11, 18));
            SettlementOrderView so3 = registerAndVerify(admin, "10003", "20003", "710301",
                    new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                    OffsetDateTime.parse("2026-11-18T10:00:00Z"));
            channel.canSeparateCents = 10000L;
            var execWith82PlatformRecv = db.executionService(channel, PLATFORM_MCH, clock);
            assertTrue(execWith82PlatformRecv.executeDue(Long.parseLong(so3.settlementOrderId()), 1)
                    instanceof SettlementDueTask.Outcome.Done);
            SeparateInput sent82 = channel.separateInputs.get(2);
            assertEquals(List.of(
                    ReceiverShare.toSelf(MERCHANT_1_MCH, 9940L),
                    ReceiverShare.toReceiver(PLATFORM_MCH, 60L)), sent82.recvDatas());
            // Self-operated same-merchant edge case combines into single recv_merchant_no share.
            assertEquals(List.of(ReceiverShare.toSelf(PLATFORM_MCH, 10000L)),
                    SettlementExecutionService.buildModeAReceiverShares(
                            PLATFORM_MCH, PLATFORM_MCH, new BigDecimal("0.60"), new BigDecimal("99.40")));
        }
    }

    @Test
    void modeAPostSplitFallbackPullsBackPlatformFeeAndSkipsWhenPlatformShareIsZero() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-26T10:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var admin = db.adminService(PLATFORM_RECEIVER);
            var channel = new RecordingSacsChannel();
            var writer = db.receiptWriter(PLATFORM_RECEIVER);
            var queryCoord = new SettlementQueryCoordinator(db.source, channel, writer, SHANGHAI, clock);
            var exec = db.executionService(channel, PLATFORM_RECEIVER, clock);
            var fallback = new SettlementFallbackService(db.source, db.ids, channel,
                    new JdbcAsyncTaskSubmitter(db.source, db.ids), SHANGHAI, clock,
                    PLATFORM_MCH, PLATFORM_RECEIVER);

            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("cfg-fb", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    "710301", 7, new BigDecimal("0.6000"), true, MERCHANT_1_MCH, MERCHANT_1_TERM, "init"));

            // Case 1: Normal ¥100.00 split (99.40 self + 0.60 platform SR) -> partial refund 50.00 pulls 0.30 from SR_PLATFORM.
            db.insertPaidPayment(11001L, 710301L, MERCHANT_1_MCH, new BigDecimal("100.00"),
                    "LOG11001", LocalDate.of(2026, 11, 18));
            SettlementOrderView so1 = registerAndVerify(admin, "11001", "21001", "710301",
                    new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                    OffsetDateTime.parse("2026-11-18T10:00:00Z"));
            channel.canSeparateCents = 10000L;
            exec.executeDue(Long.parseLong(so1.settlementOrderId()), 1);
            var sp1 = admin.listInstructions(so1.settlementOrderId(), sysQuery()).getFirst();
            channel.nextOutcome.set(new SplitOutcome(
                    sp1.separateNo(), sp1.outSeparateNo(), CmdType.SEPARATE,
                    "LOG11001", LocalDate.of(2026, 11, 18), "0", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26),
                    10000L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 10000L, 0L, null,
                    List.of(new SplitDetail(MERCHANT_1_MCH, null, 9940L, 9940L, 0L, null),
                            new SplitDetail(null, PLATFORM_RECEIVER, 60L, 60L, 0L, null))));
            queryCoord.execute(Long.parseLong(sp1.instructionId()));

            GateDecision d1 = fallback.ensureReadyForRefund(new RefundGateRequest(
                    11001L, 31001L, new BigDecimal("50.00"), new BigDecimal("100.00"), "NORMAL"));
            assertTrue(d1 instanceof GateDecision.Wait);
            assertEquals(1, channel.fallbackInputs.size());
            FallbackInput fb1 = channel.fallbackInputs.getFirst();
            assertEquals(MERCHANT_1_MCH, fb1.merchantNo());
            assertEquals(PLATFORM_RECEIVER, fb1.receiverNo());
            assertEquals(30L, fb1.totalAmountCents());

            var fbInstr1 = admin.listInstructions(so1.settlementOrderId(), sysQuery()).stream()
                    .filter(i -> "FALLBACK".equals(i.instructionType())).findFirst().orElseThrow();
            assertEquals(new BigDecimal("0.00"), fbInstr1.merchantAmount());
            assertEquals(new BigDecimal("0.30"), fbInstr1.platformAmount());
            channel.nextOutcome.set(new SplitOutcome(
                    fbInstr1.separateNo(), fbInstr1.outSeparateNo(), CmdType.FALLBACK,
                    "LOG11001", LocalDate.of(2026, 11, 18), "0", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26),
                    30L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 30L, 0L, null, List.of()));
            queryCoord.execute(Long.parseLong(fbInstr1.instructionId()));
            SettlementOrderView partRev = admin.getById(so1.settlementOrderId(), sysQuery());
            assertEquals("PARTIALLY_REVERSED", partRev.status());
            assertEquals(new BigDecimal("0.30"), partRev.reversedAmount());

            // Simulate RefundSucceededEvent updating refundedAmount = 50.00, then remaining 50.00 refund reverses final 0.30 -> REVERSED.
            admin.onRefundSucceededForOrder(21001L, new BigDecimal("50.00"), false);
            GateDecision d2 = fallback.ensureReadyForRefund(new RefundGateRequest(
                    11001L, 31002L, new BigDecimal("50.00"), new BigDecimal("100.00"), "NORMAL"));
            assertTrue(d2 instanceof GateDecision.Wait);
            assertEquals(2, channel.fallbackInputs.size());
            assertEquals(30L, channel.fallbackInputs.get(1).totalAmountCents());
            var fbInstr2 = admin.listInstructions(so1.settlementOrderId(), sysQuery()).stream()
                    .filter(i -> "FALLBACK".equals(i.instructionType()) && i.attemptNo() == 2).findFirst().orElseThrow();
            channel.nextOutcome.set(new SplitOutcome(
                    fbInstr2.separateNo(), fbInstr2.outSeparateNo(), CmdType.FALLBACK,
                    "LOG11001", LocalDate.of(2026, 11, 18), "0", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26),
                    30L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 30L, 0L, null, List.of()));
            queryCoord.execute(Long.parseLong(fbInstr2.instructionId()));
            SettlementOrderView fullRev = admin.getById(so1.settlementOrderId(), sysQuery());
            assertEquals("REVERSED", fullRev.status());
            assertEquals(new BigDecimal("0.60"), fullRev.reversedAmount());

            // Case 2: Platform-coupon order (orig 100, coupon 20, paid 80 -> split 80 self + 0 platform, subsidyDue 19.40)
            // Post-split partial refund 40.00 skips 2395 (Proceed immediately) and recomputes subsidyDue = 9.70.
            db.insertPaidPayment(11002L, 710301L, MERCHANT_1_MCH, new BigDecimal("80.00"),
                    "LOG11002", LocalDate.of(2026, 11, 18));
            SettlementOrderView so2 = registerAndVerify(admin, "11002", "21002", "710301",
                    new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"),
                    OffsetDateTime.parse("2026-11-18T10:00:00Z"));
            channel.canSeparateCents = 8000L;
            exec.executeDue(Long.parseLong(so2.settlementOrderId()), 1);
            var sp2 = admin.listInstructions(so2.settlementOrderId(), sysQuery()).getFirst();
            channel.nextOutcome.set(new SplitOutcome(
                    sp2.separateNo(), sp2.outSeparateNo(), CmdType.SEPARATE,
                    "LOG11002", LocalDate.of(2026, 11, 18), "0", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26),
                    8000L, "SUCCESS", "SUCCESS", SplitStatus.SUCCESS, 8000L, 0L, null,
                    List.of(new SplitDetail(MERCHANT_1_MCH, null, 8000L, 8000L, 0L, null))));
            queryCoord.execute(Long.parseLong(sp2.instructionId()));

            GateDecision skip = fallback.ensureReadyForRefund(new RefundGateRequest(
                    11002L, 31003L, new BigDecimal("40.00"), new BigDecimal("80.00"), "NORMAL"));
            assertTrue(skip instanceof GateDecision.Proceed);
            assertEquals(2, channel.fallbackInputs.size(), "must not submit 2395 when platformAmount==0");

            admin.onRefundSucceededForOrder(21002L, new BigDecimal("40.00"), false);
            SettlementOrderView afterPartialCouponRefund = admin.getById(so2.settlementOrderId(), sysQuery());
            assertEquals(new BigDecimal("9.70"), afterPartialCouponRefund.platformSubsidyDue());
        }
    }

    @Test
    void modeAWalletUsesMerchantAcquiringNoWithPayType01() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-26T10:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var admin = db.adminService(PLATFORM_RECEIVER);
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("cfg-w", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    "710301", 7, new BigDecimal("0.6000"), true, MERCHANT_1_MCH, MERCHANT_1_TERM, "wallet"));

            List<BalanceQueryInput> balInputs = new ArrayList<>();
            List<WithdrawD1Input> wdInputs = new ArrayList<>();
            WalletChannel walletChannel = new WalletChannel() {
                @Override
                public VerifiedBalance queryBalance(BalanceQueryInput input) {
                    balInputs.add(input);
                    return new VerifiedBalance(new BalanceResult(
                            "PAY01", input.payType(), "NORMAL",
                            new BigDecimal("500.00"), new BigDecimal("500.00"), BigDecimal.ZERO), SHA_A);
                }

                @Override
                public VerifiedWithdrawSubmit withdrawD1(WithdrawD1Input input) {
                    wdInputs.add(input);
                    return new VerifiedWithdrawSubmit(new WithdrawD1Result("JNL_MODEA_1", input.merOrderNo()), SHA_A);
                }

                @Override
                public VerifiedWithdrawOutcome queryWithdrawal(WithdrawQueryInput input) {
                    return new VerifiedWithdrawOutcome(new WithdrawOutcome(
                            input.drawJnl(), input.merOrderNo(), MERCHANT_1_MCH,
                            new BigDecimal("120.00"), new BigDecimal("0.50"), "D1",
                            DrawState.SUCCESS, "ok", "SET01", LocalDateTime.of(2026, 11, 26, 18, 0, 0)), SHA_B);
                }
            };

            var publisher = new TransactionalOutboxPublisher(db.source, db.ids, new ObjectMapper());
            var receiptWriter = new WalletReceiptWriter(db.source, db.ids, (h, b) -> {
                throw new AssertionError();
            }, publisher, SHANGHAI);
            var walletService = new WalletService(db.source, db.ids, SettlementReceiverLookup.failClosed(),
                    walletChannel, receiptWriter, new JdbcAsyncTaskSubmitter(db.source, db.ids),
                    new WalletService.Settings("1", "https://notify.invalid/withdrawals"), clock);

            MerchantWalletView view = walletService.getMerchantWallet("710301",
                    new QueryContext("tr-w", OperatorType.MERCHANT_STAFF, "8001"));
            assertTrue(view.receiverActive());
            assertEquals("ACTIVE", view.receiverStatus());
            assertEquals(new BigDecimal("500.00"), view.availableBalance());
            assertEquals(1, balInputs.size());
            assertEquals(MERCHANT_1_MCH, balInputs.getFirst().receiverNo());
            assertEquals(LakalaLaepProtocol.PAY_TYPE_MERCHANT_COLLECT, balInputs.getFirst().payType());

            WithdrawalOrderView wd = walletService.withdraw(new WithdrawCommand(
                    new CommandContext("req-wd-a", "tr-w2", OperatorType.MERCHANT_STAFF, "8001", "MINIAPP"),
                    "710301", new BigDecimal("120.00")));
            assertEquals("PROCESSING", wd.status());
            assertEquals(MERCHANT_1_MCH, wd.receiverNo());
            assertEquals(1, wdInputs.size());
            assertEquals(MERCHANT_1_MCH, wdInputs.getFirst().receiverNo());
            assertEquals(LakalaLaepProtocol.PAY_TYPE_MERCHANT_COLLECT, wdInputs.getFirst().payType());

            assertEquals(WalletQueryTask.Outcome.DONE, walletService.pollWithdrawalStatus(Long.parseLong(wd.withdrawalId())));
            assertEquals("SUCCEEDED", db.queryString("SELECT status FROM withdrawal_order WHERE id=" + wd.withdrawalId()));
        }
    }

    @Test
    void modeADailyReconcileScansAllAcquiringMerchantsAndMatchesSelfDetail() throws Exception {
        try (Db db = Db.open()) {
            Instant now = Instant.parse("2026-11-26T03:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            LocalDate reconcileDate = LocalDate.of(2026, 11, 25);
            var admin = db.adminService(PLATFORM_RECEIVER);
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("cfg-r1", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    "710301", 7, new BigDecimal("0.6000"), true, MERCHANT_1_MCH, MERCHANT_1_TERM, "m1"));
            admin.upsertConfig(new UpsertSettlementConfigCommand(
                    new CommandContext("cfg-r2", "t", OperatorType.PLATFORM_OPERATOR, "9001", "ADMIN"),
                    "710302", 7, new BigDecimal("0.6000"), true, MERCHANT_2_MCH, MERCHANT_2_TERM, "m2"));

            db.insertSucceededSplitInstruction(9001L, 8001L, 7001L, 710301L, "SP_M1", "LKL_M1",
                    MERCHANT_1_MCH, PLATFORM_RECEIVER, new BigDecimal("100.00"), new BigDecimal("99.40"),
                    new BigDecimal("0.60"), new BigDecimal("0.00"), reconcileDate);
            db.insertSucceededSplitInstruction(9002L, 8002L, 7002L, 710302L, "SP_M2", "LKL_M2",
                    MERCHANT_2_MCH, PLATFORM_RECEIVER, new BigDecimal("200.00"), new BigDecimal("198.80"),
                    new BigDecimal("1.20"), new BigDecimal("0.00"), reconcileDate);

            List<String> queriedMerchants = new ArrayList<>();
            SettlementReconcileChannel recChannel = new SettlementReconcileChannel() {
                @Override
                public VerifiedResultListPage queryResultList(QueryResultListInput input) {
                    queriedMerchants.add(input.merchantNo());
                    if (MERCHANT_1_MCH.equals(input.merchantNo())) {
                        return new VerifiedResultListPage(new QueryResultListPage(1, 1, 1, List.of(
                                new ResultListItem(MERCHANT_1_MCH, "SP_M1", "LKL_M1", "LOG1",
                                        10000L, reconcileDate, CmdType.SEPARATE, List.of(PLATFORM_RECEIVER),
                                        null, "SUCCESS", null, 10000L, null))), SHA_A);
                    }
                    return new VerifiedResultListPage(new QueryResultListPage(1, 1, 1, List.of(
                            new ResultListItem(MERCHANT_2_MCH, "SP_M2", "LKL_M2", "LOG2",
                                    20000L, reconcileDate, CmdType.SEPARATE, List.of(PLATFORM_RECEIVER),
                                    null, "SUCCESS", null, 20000L, null))), SHA_A);
                }

                @Override
                public VerifiedDetailPage queryDetail(QueryDetailInput input) {
                    // Return BOTH self detail (recvNo=merchantNo) and platform detail (recvNo=PLATFORM_RECEIVER)
                    // to prove Mode A reconcile matches self detail (99.40/198.80) and does NOT false-positive on 0.60/1.20!
                    long merchantCents = MERCHANT_1_MCH.equals(input.merchantNo()) ? 9940L : 19880L;
                    long platCents = MERCHANT_1_MCH.equals(input.merchantNo()) ? 60L : 120L;
                    return new VerifiedDetailPage(new QueryDetailPage(1, 2, 2, List.of(
                            new DetailItem(input.merchantNo(), "LKL", input.outSeparateNo(), "M", CmdType.SEPARATE,
                                    input.merchantNo(), merchantCents, "LOG", reconcileDate, "SUCCESS",
                                    reconcileDate, LocalDateTime.of(2026, 11, 25, 10, 0), null, merchantCents, 0L, null),
                            new DetailItem(input.merchantNo(), "LKL", input.outSeparateNo(), "P", CmdType.SEPARATE,
                                    PLATFORM_RECEIVER, platCents, "LOG", reconcileDate, "SUCCESS",
                                    reconcileDate, LocalDateTime.of(2026, 11, 25, 10, 0), null, platCents, 0L, null))),
                            SHA_B);
                }
            };

            var reconcile = new SettlementReconcileService(
                    db.source, db.ids, recChannel, PLATFORM_MCH, PLATFORM_RECEIVER, SHANGHAI, clock);
            ReconcileRunSummary summary = reconcile.runDailyReconcile(reconcileDate,
                    new CommandContext("rec-1", "t", OperatorType.SYSTEM, null, "SCHEDULER"));
            assertEquals("MULTI(2)", summary.merchantNo());
            assertEquals("COMPLETED", summary.status());
            assertEquals(2, summary.channelCount());
            assertEquals(2, summary.localCount());
            assertEquals(0, summary.diffCount());
            assertEquals(List.of(MERCHANT_1_MCH, MERCHANT_2_MCH), queriedMerchants);
            assertEquals(2, db.count("SELECT COUNT(*) FROM reconcile_job_run WHERE status='COMPLETED'"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM reconcile_diff"));
        }
    }

    private static SettlementOrderView registerAndVerify(
            SettlementAdminService admin, String paymentId, String orderId, String merchantId,
            BigDecimal originalPrice, BigDecimal platformSubsidy, BigDecimal paidAmount,
            OffsetDateTime verifiedAt) {
        CommandContext sys = new CommandContext("reg-" + paymentId, "t", OperatorType.SYSTEM, null, "SYS");
        SettlementOrderView registered = admin.registerForPayment(new RegisterSettlementCommand(
                sys, paymentId, orderId, "BOOKING_ORDER", orderId, merchantId, "710302",
                originalPrice, BigDecimal.ZERO, BigDecimal.ZERO, platformSubsidy, paidAmount, null));
        return admin.markVerified(new MarkSettlementVerifiedCommand(
                new CommandContext("ver-" + paymentId, "t", OperatorType.SYSTEM, null, "SYS"),
                registered.settlementOrderId(), verifiedAt, null));
    }

    private static QueryContext sysQuery() {
        return new QueryContext("t", OperatorType.SYSTEM, null);
    }

    private static final class RecordingSacsChannel implements SettlementChannel {
        long canSeparateCents = 10000L;
        final List<SeparateInput> separateInputs = new ArrayList<>();
        final List<FallbackInput> fallbackInputs = new ArrayList<>();
        final AtomicReference<SplitOutcome> nextOutcome = new AtomicReference<>();

        @Override
        public VerifiedQueryAmt querySeparableAmount(QueryAmtInput input) {
            return new VerifiedQueryAmt(new QueryAmtResult(
                    input.merchantNo(), input.logNo(), input.logDate(), canSeparateCents, canSeparateCents), SHA_A);
        }

        @Override
        public VerifiedSeparateSubmit submitSeparate(SeparateInput input) {
            separateInputs.add(input);
            return new VerifiedSeparateSubmit(new SeparateSubmitResult(
                    input.outSeparateNo(), "SEP_" + input.outSeparateNo(), SplitStatus.PROCESSING,
                    "PROCESSING", input.logNo(), input.logDate(), input.totalAmountCents()), SHA_A);
        }

        @Override
        public VerifiedFallbackSubmit submitFallback(FallbackInput input) {
            fallbackInputs.add(input);
            return new VerifiedFallbackSubmit(new FallbackSubmitResult(
                    input.outSeparateNo(), "FB_" + input.outSeparateNo(),
                    input.originSeparateNo(), input.originOutSeparateNo(),
                    input.totalAmountCents(), SplitStatus.PROCESSING, "PROCESSING"), SHA_A);
        }

        @Override
        public VerifiedSplitOutcome querySeparateOutcome(QueryPlusInput input, ExpectedSplit expected) {
            SplitOutcome out = nextOutcome.get();
            if (out == null) throw new AssertionError("nextOutcome not set");
            return new VerifiedSplitOutcome(out, SHA_B);
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
        final AtomicLong seq = new AtomicLong(950_000_000L);
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
            String dbName = "paymodea_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

        SettlementAdminService adminService(String platformReceiverNo) {
            return new SettlementAdminService(source, ids, new JdbcAsyncTaskSubmitter(source, ids),
                    null, PLATFORM_MCH, platformReceiverNo);
        }

        SettlementExecutionService executionService(SettlementChannel channel, String platformReceiverNo, Clock clock) {
            return new SettlementExecutionService(source, ids, channel, SettlementReceiverLookup.failClosed(),
                    SettlementOrderBlockerPort.none(), new JdbcAsyncTaskSubmitter(source, ids),
                    new SettlementExecutionService.Settings(
                            PLATFORM_MCH, SHANGHAI, BigDecimal.ZERO, "1", "https://notify.invalid/split",
                            platformReceiverNo),
                    clock);
        }

        SettlementReceiptWriter receiptWriter(String platformReceiverNo) {
            return new SettlementReceiptWriter(source, ids, (h, b) -> {
                throw new AssertionError();
            }, event -> {}, PLATFORM_MCH, platformReceiverNo);
        }

        void insertPaidPayment(long paymentId, long merchantId, String merchantNo, BigDecimal amount,
                String logNo, LocalDate logDate) throws Exception {
            String sql = "INSERT INTO payment_order(id,payment_no,order_id,biz_type,biz_no,store_id,merchant_id,user_id,"
                    + "amount,status,channel,expire_at,merchant_no,term_no,sub_appid,currency,dispatch_state,"
                    + "channel_trade_no,channel_paid_amount,paid_at,settle_type,log_no,log_date,created_at,updated_at) "
                    + "VALUES(?,?,?,NULL,NULL,710302,?,9999,?,'PAID','LAKALA_WECHAT',UTC_TIMESTAMP(3),?,'T1',?,"
                    + "'CNY','OBSERVED',?,?,UTC_TIMESTAMP(3),'1',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))";
            try (Connection c = source.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, paymentId);
                ps.setLong(2, paymentId + 100_000L);
                ps.setLong(3, paymentId + 10_000L);
                ps.setLong(4, merchantId);
                ps.setBigDecimal(5, amount);
                ps.setString(6, merchantNo);
                ps.setString(7, PLATFORM_APP);
                ps.setString(8, "20261118" + (66210306990000L + paymentId));
                ps.setBigDecimal(9, amount);
                ps.setString(10, logNo);
                ps.setObject(11, logDate);
                ps.executeUpdate();
            }
        }

        void insertSucceededSplitInstruction(long id, long soId, long paymentId, long merchantId,
                String outSepNo, String sepNo, String merchantNo, String receiverNo,
                BigDecimal totalAmt, BigDecimal merchantAmt, BigDecimal platformAmt, BigDecimal feeAmt,
                LocalDate finishDate) throws Exception {
            try (Connection c = source.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO settlement_instruction(id,settlement_order_id,payment_id,merchant_id,"
                                 + "instruction_type,attempt_no,out_separate_no,separate_no,merchant_no,receiver_no,"
                                 + "log_no,log_date,total_amount,merchant_amount,platform_amount,"
                                 + "actual_separate_amount,actual_merchant_amount,total_fee_amount,merchant_fee_amount,"
                                 + "status,finish_date,sent_at,terminal_at,version,created_at,updated_at) "
                                 + "VALUES(?,?,?,?,'SPLIT',1,?,?,?,?, 'LOG', ?, ?,?,?, ?,?,?,?, 'SUCCEEDED', ?, ?, ?, 0, ?, ?)")) {
                Timestamp ts = Timestamp.from(finishDate.atTime(10, 0).toInstant(ZoneOffset.UTC));
                ps.setLong(1, id);
                ps.setLong(2, soId);
                ps.setLong(3, paymentId);
                ps.setLong(4, merchantId);
                ps.setString(5, outSepNo);
                ps.setString(6, sepNo);
                ps.setString(7, merchantNo);
                ps.setString(8, receiverNo);
                ps.setObject(9, finishDate);
                ps.setBigDecimal(10, totalAmt);
                ps.setBigDecimal(11, merchantAmt);
                ps.setBigDecimal(12, platformAmt);
                ps.setBigDecimal(13, totalAmt);
                ps.setBigDecimal(14, merchantAmt);
                ps.setBigDecimal(15, feeAmt);
                ps.setBigDecimal(16, feeAmt);
                ps.setObject(17, finishDate);
                ps.setTimestamp(18, ts);
                ps.setTimestamp(19, ts);
                ps.setTimestamp(20, ts);
                ps.setTimestamp(21, ts);
                ps.executeUpdate();
            }
        }

        int count(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        }

        String queryString(String sql) throws Exception {
            try (Connection c = source.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
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
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : v;
        }

        private static Path repoRoot() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return java.util.Objects.requireNonNull(p);
        }
    }
}
