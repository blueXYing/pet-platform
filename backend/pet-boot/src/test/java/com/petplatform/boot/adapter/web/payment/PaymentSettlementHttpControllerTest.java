package com.petplatform.boot.adapter.web.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petplatform.admin.api.dto.AdminDataScope;
import com.petplatform.admin.api.dto.AdminPermissionSnapshot;
import com.petplatform.admin.api.dto.AdminSessionPrincipal;
import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.PaymentSettlementHttpConfiguration;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.dto.MerchantMembershipDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.payment.api.command.ReceiverAccountApi;
import com.petplatform.payment.api.command.ReconcileAdminApi;
import com.petplatform.payment.api.command.SettlementAdminApi;
import com.petplatform.payment.api.command.WalletCommandApi;
import com.petplatform.payment.api.dto.ReceiverTypes.MerchantReceiverStatusView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverSensitiveView;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileDiffView;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportResponse;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportRow;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportSummary;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementConfigView;
import com.petplatform.payment.api.dto.WalletTypes.MerchantWalletView;
import com.petplatform.payment.api.dto.WalletTypes.WalletPage;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import com.petplatform.payment.api.query.ReceiverQueryApi;
import com.petplatform.payment.api.query.SettlementQueryApi;
import com.petplatform.payment.api.query.SubsidyReportQueryApi;
import com.petplatform.payment.api.query.WalletQueryApi;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.center.PaymentCallbackRouter;
import com.petplatform.payment.biz.settlement.ReceiverAccountService;
import com.petplatform.payment.biz.settlement.SettlementReceiptWriter;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Contract 80 Stage 7-BE HTTP tests:
 * <ul>
 *   <li>Unified {@code pet.payment.enabled} (+ admin/c base switch) controller wiring with zero sub-switches</li>
 *   <li>AdminSettlementController RBAC enforcement (401/403), X-Request-Id checks, read-only subsidy/withdrawals</li>
 *   <li>MerchantWalletController OWNER-only enforcement (STAFF -> 403), no SMS OTP / extra fields, read-only receiver</li>
 *   <li>LakalaCallbackController 1009 raw-byte delegation, exact Lakala SUCCESS ack, bad-signature 400</li>
 * </ul>
 */
class PaymentSettlementHttpControllerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 25, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void httpControllersFollowBaseSwitchesWithoutSubSwitches() {
        new ApplicationContextRunner()
                .withUserConfiguration(PaymentSettlementHttpConfiguration.class,
                        AdminSettlementController.class, MerchantWalletController.class,
                        LakalaCallbackController.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(AdminSettlementController.class);
                    assertThat(ctx).doesNotHaveBean(MerchantWalletController.class);
                    assertThat(ctx).doesNotHaveBean(LakalaCallbackController.class);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(PaymentSettlementHttpConfiguration.class,
                        AdminSettlementController.class, MerchantWalletController.class,
                        LakalaCallbackController.class)
                .withPropertyValues(
                        "pet.payment.enabled=true",
                        "pet.auth.admin.enabled=true",
                        "pet.auth.c.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(AdminSettlementController.class);
                    assertThat(ctx).hasSingleBean(MerchantWalletController.class);
                    assertThat(ctx).hasSingleBean(LakalaCallbackController.class);
                });
    }

    @Test
    void adminControllerEnforcesRbacRequestIdAndReadOnlyBoundaries() throws Exception {
        SettlementAdminApi settlementAdmin = mock(SettlementAdminApi.class);
        SettlementQueryApi settlementQuery = mock(SettlementQueryApi.class);
        ReceiverAccountApi receiverAccount = mock(ReceiverAccountApi.class);
        ReceiverQueryApi receiverQuery = mock(ReceiverQueryApi.class);
        WalletQueryApi walletQuery = mock(WalletQueryApi.class);
        SubsidyReportQueryApi subsidyQuery = mock(SubsidyReportQueryApi.class);
        ReconcileAdminApi reconcileAdmin = mock(ReconcileAdminApi.class);

        when(settlementAdmin.upsertConfig(any())).thenReturn(new SettlementConfigView(
                "5001", 10, new BigDecimal("0.6500"), true, true, "82266507295001Z", "Q6426723", 1L, NOW));
        when(receiverAccount.revealSensitive(any())).thenReturn(new ReceiverSensitiveView(
                "5001", "6222021234567890", "310101199001011234", "13800138000", null, null));
        when(subsidyQuery.querySubsidyReport(any())).thenReturn(new SubsidyReportResponse(
                new SubsidyReportSummary(2L, new BigDecimal("198.80"), new BigDecimal("140.00"), new BigDecimal("58.80")),
                List.of(new SubsidyReportRow("5001", "2026-11", 2L, new BigDecimal("140.00"),
                        new BigDecimal("198.80"), new BigDecimal("140.00"), new BigDecimal("0.00"),
                        new BigDecimal("58.80"))),
                1L, 1, 20));
        when(reconcileAdmin.resolveDiff(any())).thenReturn(new ReconcileDiffView(
                "8001", LocalDate.of(2026, 11, 24), "SPLIT", "FEE_MISMATCH",
                "1001", "3001", "SEP1", "LKL1", "5001",
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                new BigDecimal("0.60"), new BigDecimal("0.90"),
                "SUCCEEDED", "SUCCESS", "fee diff", "RESOLVED", "9001", "verified", NOW, NOW));

        AdminSettlementController controller = new AdminSettlementController(
                provider(settlementAdmin), provider(settlementQuery), provider(receiverAccount),
                provider(receiverQuery), provider(walletQuery), provider(subsidyQuery), provider(reconcileAdmin));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PaymentSettlementHttpExceptionHandler())
                .build();

        // 1. No session -> 401
        mvc.perform(get("/api/v1/admin/settlement/subsidy-report"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        // 2. Read-only operator cannot write config or reveal sensitive -> 403
        AdminSessionView readOnly = adminSession("9001", List.of("settlement.report.read"));
        mvc.perform(put("/api/v1/admin/settlement/configs/5001")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly)
                        .header("X-Request-Id", "req-cfg-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settleDays\":10,\"feeRate\":\"0.6500\",\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        mvc.perform(post("/api/v1/admin/settlement/receivers/5001/reveal")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"check\"}"))
                .andExpect(status().isForbidden());

        // 3. Read-only subsidy report succeeds with string amounts
        mvc.perform(get("/api/v1/admin/settlement/subsidy-report")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.totalSubsidyDue").value("58.80"))
                .andExpect(jsonPath("$.data.items[0].merchantId").value("5001"));

        // 4. Write config missing X-Request-Id -> 400; with X-Request-Id -> 200
        AdminSessionView writer = adminSession("9001", List.of(
                "settlement.config.write", "settlement.receiver.read-sensitive", "settlement.instruction.retry"));
        mvc.perform(put("/api/v1/admin/settlement/configs/5001")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settleDays\":10,\"feeRate\":\"0.6500\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/admin/settlement/configs/5001")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", "11111111-1111-4111-8111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settleDays\":10,\"feeRate\":\"0.6500\",\"enabled\":true,"
                                + "\"lakalaMerchantNo\":\"82266507295001Z\",\"lakalaTermNo\":\"Q6426723\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeRate").value("0.6500"))
                .andExpect(jsonPath("$.data.settleDays").value(10))
                .andExpect(jsonPath("$.data.lakalaMerchantNo").value("82266507295001Z"))
                .andExpect(jsonPath("$.data.lakalaTermNo").value("Q6426723"));

        // 5. Audited reveal & diff resolve succeed
        mvc.perform(post("/api/v1/admin/settlement/receivers/5001/reveal")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"audit check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acctNo").value("6222021234567890"));

        mvc.perform(post("/api/v1/admin/settlement/reconcile-diffs/8001/resolve")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", "22222222-2222-4222-8222-222222222222")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        // 6. Ruling 22 / 40: zero POST on subsidy-report or withdrawals
        mvc.perform(post("/api/v1/admin/settlement/subsidy-report")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/admin/settlement/withdrawals")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void merchantWalletControllerEnforcesOwnerOnlyNoSmsOtpAndReadOnlyReceiver() throws Exception {
        MerchantAdmissionQueryApi admission = mock(MerchantAdmissionQueryApi.class);
        ReceiverQueryApi receiverQuery = mock(ReceiverQueryApi.class);
        WalletQueryApi walletQuery = mock(WalletQueryApi.class);
        WalletCommandApi walletCommand = mock(WalletCommandApi.class);

        when(receiverQuery.getMerchantStatusReadOnly(eq("5001"), any())).thenReturn(
                new MerchantReceiverStatusView("ACTIVE", "已生效", "SR1", "张*", "8821", "工商银行", true, null, NOW));
        when(walletQuery.getMerchantWallet(eq("5001"), any())).thenReturn(
                new MerchantWalletView("ACTIVE", true, new BigDecimal("680.50"),
                        new BigDecimal("240.00"), 3L, "CNY", NOW, true));
        when(walletQuery.listWithdrawals(any())).thenReturn(new WalletPage<>(List.of(), 0L, 1, 20));
        when(walletCommand.withdraw(any())).thenReturn(new WithdrawalOrderView(
                "9001", "WD9001", "5001", "SR1", new BigDecimal("100.00"),
                null, null, "PROCESSING", "JNL1", null, "DRAW.ACCEPTED", "7001", NOW, null, NOW));

        MerchantWalletController controller = new MerchantWalletController(
                provider(admission), provider(receiverQuery), provider(walletQuery), provider(walletCommand));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PaymentSettlementHttpExceptionHandler())
                .build();

        MiniSessionView staffSession = new MiniSessionView("101", "7002", Instant.now().plusSeconds(3600), "138****0002", "ACTIVE");
        MiniSessionView ownerSession = new MiniSessionView("102", "7001", Instant.now().plusSeconds(3600), "138****0001", "ACTIVE");

        // 1. STAFF (non-OWNER) blocked with 403
        when(admission.listMemberships(any())).thenReturn(new MerchantMembershipPageDTO(
                List.of(new MerchantMembershipDTO("5001", "Shop", "6001", "Store", "STAFF")), 1, 50, 1L));
        mvc.perform(get("/api/v1/merchant/wallet")
                        .requestAttr(CBearerSessionFilter.VIEW, staffSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        // 2. OWNER reads wallet & receiver status cleanly
        when(admission.listMemberships(any())).thenReturn(new MerchantMembershipPageDTO(
                List.of(new MerchantMembershipDTO("5001", "Shop", "6001", "Store", "OWNER")), 1, 50, 1L));
        mvc.perform(get("/api/v1/merchant/wallet")
                        .requestAttr(CBearerSessionFilter.VIEW, ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableBalance").value("680.50"))
                .andExpect(jsonPath("$.data.pendingSettlementCount").value(3))
                .andExpect(jsonPath("$.data.platformSubsidyDue").doesNotExist());

        mvc.perform(get("/api/v1/merchant/settlement/receiver")
                        .requestAttr(CBearerSessionFilter.VIEW, ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.acctNoLast4").value("8821"));

        // 3. OWNER withdraw rejects extra fields (e.g. smsCode) -> 400; valid {amount} + X-Request-Id -> 200
        mvc.perform(post("/api/v1/merchant/wallet/withdrawals")
                        .requestAttr(CBearerSessionFilter.VIEW, ownerSession)
                        .header("X-Request-Id", "req-wd-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100.00\",\"smsCode\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/merchant/wallet/withdrawals")
                        .requestAttr(CBearerSessionFilter.VIEW, ownerSession)
                        .header("X-Request-Id", "33333333-3333-4333-8333-333333333333")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.amount").value("100.00"));

        // 4. Ruling 40 §4.2: merchant cannot edit receiver (POST/PUT rejected)
        mvc.perform(post("/api/v1/merchant/settlement/receiver")
                        .requestAttr(CBearerSessionFilter.VIEW, ownerSession))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void lakalaCallbackControllerVerifiesDelegationAndReturnsExactAck() throws Exception {
        PaymentCallbackRouter payRouter = mock(PaymentCallbackRouter.class);
        SettlementReceiptWriter sepWriter = mock(SettlementReceiptWriter.class);
        WalletCommandApi walletCmd = mock(WalletCommandApi.class);
        ReceiverAccountService receiverSvc = mock(ReceiverAccountService.class);

        when(payRouter.receive(any(), any())).thenReturn(
                new PaymentNotificationService.ReceiptResult("1", false, true));
        when(sepWriter.receiveNotification(any(), any())).thenReturn(
                new SettlementReceiptWriter.ReceiptResult("1", "1", false, true, true));
        doNothing().when(walletCmd).handleWithdrawalNotification(any(), any());
        when(receiverSvc.receiveBindNotification(any(), any())).thenReturn(null);

        LakalaCallbackController controller = new LakalaCallbackController(
                provider(payRouter), provider(sepWriter), provider(walletCmd), provider(receiverSvc));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        byte[] payload = "{\"test\":true}".getBytes(StandardCharsets.UTF_8);
        String auth = "LKLAPI-SHA256withRSA timestamp=\"1764050000\",nonce_str=\"abc\",signature=\"sig\"";

        for (String path : List.of("/pay", "/separate", "/withdrawals", "/ledger")) {
            mvc.perform(post("/api/v1/callbacks/lakala" + path)
                            .header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.message").value("执行成功"));
        }
        verify(walletCmd).handleWithdrawalNotification(any(Map.class), eq(payload));

        // Bad signature -> 400 FAIL
        doThrow(new ApiException(CommonApiCodes.INVALID_ARGUMENT, "bad sig"))
                .when(walletCmd).handleWithdrawalNotification(any(), any());
        mvc.perform(post("/api/v1/callbacks/lakala/withdrawals")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"));
    }

    private static AdminSessionView adminSession(String operatorId, List<String> actions) {
        return new AdminSessionView(
                new AdminSessionPrincipal("ADMIN_WEB", "1001", operatorId, 1L),
                NOW.plusHours(1),
                new AdminPermissionSnapshot(operatorId, "1", NOW, List.of(),
                        new AdminDataScope("ALL", List.of(), List.of()), actions));
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return instance; }
            @Override public T getObject(Object... args) { return instance; }
            @Override public T getIfAvailable() { return instance; }
            @Override public T getIfUnique() { return instance; }
            @Override public Stream<T> stream() { return instance == null ? Stream.empty() : Stream.of(instance); }
        };
    }
}
