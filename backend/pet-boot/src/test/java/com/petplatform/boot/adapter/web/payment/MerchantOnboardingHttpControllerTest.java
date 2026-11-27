package com.petplatform.boot.adapter.web.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petplatform.admin.api.dto.AdminDataScope;
import com.petplatform.admin.api.dto.AdminPermissionSnapshot;
import com.petplatform.admin.api.dto.AdminSessionPrincipal;
import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.payment.api.command.MerchantOnboardingApi;
import com.petplatform.payment.api.dto.OnboardingTypes.FeeRateView;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAssetView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAuditView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingDefaultsView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingPage;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingSensitiveView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingStepView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingView;
import com.petplatform.payment.api.dto.OnboardingTypes.PlatformDefaultsView;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.query.MerchantOnboardingQueryApi;
import com.petplatform.payment.biz.settlement.MerchantOnboardingService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Contract 81 HTTP surface: RBAC action codes, X-Request-Id, unknown-field rejection, exact wire shapes of
 * the operations endpoints (§4), and the status mapping of the four Lakala onboarding callbacks (§5).
 */
class MerchantOnboardingHttpControllerTest {
    private static final String ROOT = "/api/v1/admin/settlement/onboardings";
    private static final String REQUEST_ID = "0b9f7fd4-6b3e-4f7d-9c11-2d5e8f3a7b11";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 27, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F'};
    private static final byte[] TEXT = "not an image".getBytes(StandardCharsets.UTF_8);

    private MerchantOnboardingApi commands;
    private MerchantOnboardingQueryApi queries;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        commands = mock(MerchantOnboardingApi.class);
        queries = mock(MerchantOnboardingQueryApi.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminMerchantOnboardingController(
                        provider(commands), provider(queries)))
                .setControllerAdvice(new PaymentSettlementHttpExceptionHandler())
                .build();
    }

    @Test
    void requiresASessionAndTheContractActionCodes() throws Exception {
        when(queries.defaults(anyString(), any())).thenReturn(new OnboardingDefaultsView("9001", true, null,
                "示例宠物医院", "PET_HOSPITAL", "chengdu", "510104", true, List.of(),
                new PlatformDefaultsView("CLOUD_DISTRIBUTION", "654", "T+1", "0742", List.of(), "90.50", true)));
        mvc.perform(get(ROOT + "/9001")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/9001/defaults")).andExpect(status().isUnauthorized());

        AdminSessionView readOnly = session(List.of("settlement.report.read"));
        mvc.perform(get(ROOT + "/9001/defaults").requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly))
                .andExpect(status().isOk());
        mvc.perform(put(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mvc.perform(post(ROOT + "/9001/submit").requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/9001/reveal").requestAttr(AdminBearerAuthenticationFilter.VIEW, readOnly)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"核对\"}"))
                .andExpect(status().isForbidden());
        // The onboarding writer may read defaults but needs the separate sensitive-read code to reveal.
        AdminSessionView writer = session(List.of("settlement.onboarding.write"));
        when(queries.defaults(eq("9001"), any())).thenReturn(new OnboardingDefaultsView("9001", true, null,
                "示例宠物医院", "PET_HOSPITAL", "chengdu", "510104", true, List.of(),
                new PlatformDefaultsView("CLOUD_DISTRIBUTION", "654", "T+1", "0742", List.of(), "90.50", true)));
        mvc.perform(get(ROOT + "/9001/defaults").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/9001/reveal").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"核对\"}"))
                .andExpect(status().isForbidden());
        verify(commands, never()).reveal(any());
    }

    @Test
    void defaultsAndDetailFollowTheWireShape() throws Exception {
        when(queries.defaults(eq("9001"), any())).thenReturn(new OnboardingDefaultsView("9001", true, null,
                "示例宠物医院", "PET_HOSPITAL", "chengdu", "510104", false, List.of("posType", "feeRates"),
                new PlatformDefaultsView(null, "654", "T+1", "0742", List.of(
                        new FeeRateView("302", "微信", "0.60")), null, true)));
        mvc.perform(get(ROOT + "/9001/defaults").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                        session(List.of("settlement.report.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.configReady").value(false))
                .andExpect(jsonPath("$.data.missingConfig[0]").value("posType"))
                .andExpect(jsonPath("$.data.districtCode").value("510104"))
                .andExpect(jsonPath("$.data.platform.mccCode").value("0742"))
                .andExpect(jsonPath("$.data.platform.feeRates[0].feeRatePct").value("0.60"))
                .andExpect(headerNoStore());
        when(queries.getByMerchantId(eq("9001"), any()))
                .thenThrow(new ApiException(CommonApiCodes.NOT_FOUND, "开通资料不存在"));
        mvc.perform(get(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                        session(List.of("settlement.report.read"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        verify(queries).getByMerchantId(eq("9001"), any());
        mvc.perform(get(ROOT + "/abc").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                session(List.of("settlement.report.read")))).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "?page=0").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                session(List.of("settlement.report.read")))).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "?unexpected=1").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                session(List.of("settlement.report.read")))).andExpect(status().isBadRequest());
    }

    @Test
    void saveParsesTheProfileAndRejectsUnknownFieldsOrMissingRequestId() throws Exception {
        AdminSessionView writer = session(List.of("settlement.onboarding.write"));
        String body = "{\"expectedVersion\":3,\"merchant\":{\"merRegName\":\"示例宠物医院\",\"merBizName\":null,"
                + "\"merRegDistCode\":\"510104\",\"merRegAddr\":\"天府大道1号\",\"mccCode\":\"0742\","
                + "\"merBlisName\":\"示例宠物医院\",\"merBlisNo\":\"91110108MA2G7LK368\",\"merBlisStDt\":\"2020-01-01\","
                + "\"merBlisExpDt\":\"2040-01-01\",\"larName\":\"张三\",\"larIdcard\":\"342523199905298212\","
                + "\"larIdcardStDt\":\"1999-05-29\",\"larIdcardExpDt\":\"2039-05-29\",\"larMobile\":\"13800001234\","
                + "\"contactName\":\"李四\",\"contactMobile\":\"13900000000\"},\"settlement\":{"
                + "\"acctTypeCode\":\"58\",\"acctName\":\"张三\",\"acctNo\":\"6212260200102400000\",\"acctIdType\":null,"
                + "\"acctIdcard\":null,\"acctIdExpDt\":null,\"openningBankCode\":\"102100000030\","
                + "\"openningBankName\":\"中国工商银行北京市分行营业部\",\"clearingBankCode\":\"102100099996\"},"
                + "\"attachments\":{\"BANK_CARD\":\"103\",\"SHOPINNER\":\"\"},\"cooperationAssetId\":\"201\","
                + "\"reason\":\"线下提供\"}";
        when(commands.saveProfile(any())).thenReturn(view("DRAFT", 4, "SAVE_PROFILE", "SUBMIT"));
        mvc.perform(put(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"larName\"", "\"larNames\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"expectedVersion\":3", "\"expectedVersion\":\"3\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"SHOPINNER\":\"\"", "\"OTHERS\":\"301\"")))
                .andExpect(status().isBadRequest());
        verify(commands, never()).saveProfile(any());

        mvc.perform(put(ROOT + "/9001").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(headerNoStore())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.merchant.larIdcardMasked").value("342****8212"))
                .andExpect(jsonPath("$.data.merchant.merBlisStDt").value("2020-01-01"))
                .andExpect(jsonPath("$.data.allowedActions[1]").value("SUBMIT"))
                .andExpect(jsonPath("$.data.steps[0].step").value("EC"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-11-27T08:00:00.000Z"));
        ArgumentCaptor<SaveOnboardingCommand> captor = ArgumentCaptor.forClass(SaveOnboardingCommand.class);
        verify(commands).saveProfile(captor.capture());
        SaveOnboardingCommand saved = captor.getValue();
        assertThat(saved.merchantId()).isEqualTo("9001");
        assertThat(saved.expectedVersion()).isEqualTo(3L);
        assertThat(saved.merchant().larIdcard()).isEqualTo("342523199905298212");
        assertThat(saved.merchant().merBizName()).isNull();
        assertThat(saved.settlement().acctNo()).isEqualTo("6212260200102400000");
        assertThat(saved.settlement().acctIdType()).isNull();
        // Blank attachment values are dropped; only real asset ids travel.
        assertThat(saved.attachments()).containsExactlyInAnyOrderEntriesOf(Map.of("BANK_CARD", "103"));
        assertThat(saved.cooperationAssetId()).isEqualTo("201");
        assertThat(saved.reason()).isEqualTo("线下提供");
        assertThat(saved.context().requestId()).isEqualTo(REQUEST_ID);
        assertThat(saved.context().operatorType().name()).isEqualTo("PLATFORM_OPERATOR");
        assertThat(saved.context().operatorId()).isEqualTo("9001");
    }

    @Test
    void actionsForwardVersionedCommandsAndSurfaceTheServiceResult() throws Exception {
        AdminSessionView writer = session(List.of("settlement.onboarding.write"));
        when(commands.submit(any())).thenReturn(view("EC_SIGNING", 2, "SAVE_PROFILE", "SYNC"));
        when(commands.sync(any())).thenReturn(view("EC_SIGNING", 2, "SAVE_PROFILE", "SYNC"));
        when(commands.retry(any())).thenReturn(view("LEDGER_APPLYING", 6, "SYNC"));
        when(commands.adoptContract(any())).thenReturn(view("CONTRACT_APPLYING", 5, "SYNC"));
        when(commands.discardContract(any())).thenReturn(view("DRAFT", 5, "SAVE_PROFILE", "SUBMIT"));

        mvc.perform(post(ROOT + "/9001/submit").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/9001/submit").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"extra\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/9001/submit").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EC_SIGNING"))
                .andExpect(jsonPath("$.data.ecSignUrl").value("https://sign.example/x"))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("SAVE_PROFILE"));
        verify(commands).submit(argThat(c -> command(c.context()) && "9001".equals(c.merchantId())
                && c.expectedVersion() == 1L));

        mvc.perform(post(ROOT + "/9001/sync").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/9001/sync").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":1}"))
                .andExpect(status().isBadRequest());
        verify(commands).sync(argThat(c -> command(c.context()) && "9001".equals(c.merchantId())));

        mvc.perform(post(ROOT + "/9001/retry").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5,\"cooperationAssetId\":\"999\",\"reason\":\"换协议\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LEDGER_APPLYING"));
        verify(commands).retry(argThat(c -> command(c.context()) && "9001".equals(c.merchantId())
                && c.expectedVersion() == 5L && "999".equals(c.cooperationAssetId()) && "换协议".equals(c.reason())));

        mvc.perform(post(ROOT + "/9001/contract/adopt").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4,\"contractId\":\"202104062502898611\",\"reason\":\"已核实\"}"))
                .andExpect(status().isOk());
        verify(commands).adoptContract(argThat(c -> command(c.context()) && "9001".equals(c.merchantId())
                && c.expectedVersion() == 4L && "202104062502898611".equals(c.contractId())
                && "已核实".equals(c.reason())));

        mvc.perform(post(ROOT + "/9001/contract/discard").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4,\"reason\":\"拉卡拉确认无此进件\"}"))
                .andExpect(status().isOk());
        verify(commands).discardContract(argThat(c -> command(c.context()) && "9001".equals(c.merchantId())
                && c.expectedVersion() == 4L && "拉卡拉确认无此进件".equals(c.reason())));

        // Service failures keep their code-specific status instead of turning into a 500.
        when(commands.submit(any())).thenThrow(new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "平台级配置缺失，无法发起开通：posType"));
        mvc.perform(post(ROOT + "/9001/submit").requestAttr(AdminBearerAuthenticationFilter.VIEW, writer)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("平台级配置缺失，无法发起开通：posType"));
    }

    @Test
    void revealAndAuditsAreSeparatelyAuthorizedAndShaped() throws Exception {
        AdminSessionView sensitive = session(List.of("settlement.onboarding.read-sensitive", "settlement.report.read"));
        when(commands.reveal(any())).thenReturn(new OnboardingSensitiveView("9001", "91110108MA2G7LK368",
                "张三", "342523199905298212", "13800001234", "李四", "13900000000", "张三",
                "6212260200102400000", null, null));
        mvc.perform(post(ROOT + "/9001/reveal").requestAttr(AdminBearerAuthenticationFilter.VIEW, sensitive)
                        .header("X-Request-Id", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"商家来电核对\"}"))
                .andExpect(status().isOk())
                .andExpect(headerNoStore())
                .andExpect(jsonPath("$.data.acctNo").value("6212260200102400000"))
                .andExpect(jsonPath("$.data.larIdcard").value("342523199905298212"));
        verify(commands).reveal(argThat(c -> command(c.context()) && "9001".equals(c.merchantId())
                && "商家来电核对".equals(c.reason())));

        when(queries.listAudits(eq("9001"), any())).thenReturn(List.of(
                new OnboardingAuditView("77", "CONTRACT_REJECTED", "SYSTEM", null, null,
                        "{\"contractStatus\":\"INNER_CHECK_REJECTED\"}", NOW),
                new OnboardingAuditView("76", "PROFILE_SAVE", "PLATFORM_OPERATOR", "7", "线下提供", null, NOW)));
        mvc.perform(get(ROOT + "/9001/audits").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                        session(List.of("settlement.report.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actionType").value("CONTRACT_REJECTED"))
                .andExpect(jsonPath("$.data[0].detail.contractStatus").value("INNER_CHECK_REJECTED"))
                .andExpect(jsonPath("$.data[1].operatorId").value("7"))
                .andExpect(jsonPath("$.data[1].detail").isEmpty());

        when(queries.list(any())).thenReturn(new OnboardingPage(List.of(), 0, 1, 20));
        mvc.perform(get(ROOT + "?status=DRAFT&page=2&size=50").requestAttr(AdminBearerAuthenticationFilter.VIEW,
                        session(List.of("settlement.report.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        verify(queries).list(argThat(q -> "DRAFT".equals(q.status()) && q.page() == 2 && q.size() == 50
                && q.merchantId() == null));
    }

    @Test
    void uploadTakesOneImagePartAndSniffsTheRealType() throws Exception {
        AdminSessionView writer = session(List.of("settlement.onboarding.write"));
        when(commands.uploadAttachment(any(), eq("9001"), anyString(), anyLong(), any()))
                .thenAnswer(call -> new OnboardingAssetView("555", call.getArgument(2), 12L));
        mvc.perform(multipart(ROOT + "/9001/assets").file(png()).requestAttr(AdminBearerAuthenticationFilter.VIEW, writer))
                .andExpect(status().isBadRequest());
        // A text file is refused before anything reaches the private-asset store, whatever it claims to be.
        mvc.perform(multipart(ROOT + "/9001/assets").file(new MockMultipartFile("file", "a.png", "image/png", TEXT))
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(ROOT + "/9001/assets").file(png()).file(png())
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isBadRequest());
        verify(commands, never()).uploadAttachment(any(), anyString(), anyString(), anyLong(), any());

        mvc.perform(multipart(ROOT + "/9001/assets").file(png())
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetId").value("555"))
                .andExpect(jsonPath("$.data.mediaType").value("image/png"));
        // The declared content type is ignored: a JPEG named .png is stored as image/jpeg.
        mvc.perform(multipart(ROOT + "/9001/assets").file(new MockMultipartFile("file", "a.png", "image/png", JPEG))
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, writer).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaType").value("image/jpeg"));
        verify(commands).uploadAttachment(any(), eq("9001"), eq("image/png"), eq(12L), any());
    }

    @Test
    void onboardingCallbacksVerifyNothingThemselvesAndMapServiceOutcomes() throws Exception {
        MerchantOnboardingService nobody = null;
        MockMvc absent = MockMvcBuilders.standaloneSetup(new LakalaCallbackController(
                provider(null), provider(null), provider(null), provider(null), provider(nobody))).build();
        MerchantOnboardingService service = mock(MerchantOnboardingService.class);
        MockMvc callbacks = MockMvcBuilders.standaloneSetup(new LakalaCallbackController(
                provider(null), provider(null), provider(null), provider(null), provider(service))).build();
        String auth = "LKLAPI-SHA256withRSA timestamp=\"1764050000\",nonce_str=\"abc\",signature=\"sig\"";
        byte[] payload = "{\"contractId\":\"202104062502898611\"}".getBytes(StandardCharsets.UTF_8);

        // Without the onboarding service every onboarding callback fails closed.
        for (String path : List.of("", "/ec", "/ledger-open", "/bind")) {
            absent.perform(post("/api/v1/callbacks/lakala/onboard" + path)
                            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isServiceUnavailable());
        }

        for (String path : List.of("", "/ec", "/ledger-open", "/bind")) {
            callbacks.perform(post("/api/v1/callbacks/lakala/onboard" + path)
                            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"));
        }
        verify(service).receiveContractNotification(any(Map.class), eq(payload));
        verify(service).receiveEcNotification(any(Map.class), eq(payload));
        verify(service).receiveLedgerOpenNotification(any(Map.class), eq(payload));
        verify(service).receiveBindNotification(any(Map.class), eq(payload));

        doThrow(new ApiException(CommonApiCodes.INVALID_ARGUMENT, "验签或报文校验失败"))
                .when(service).receiveContractNotification(any(), any());
        doThrow(new ApiException(CommonApiCodes.NOT_FOUND, "进件不存在"))
                .when(service).receiveLedgerOpenNotification(any(), any());
        doThrow(new ApiException(CommonApiCodes.CONFLICT, "状态冲突"))
                .when(service).receiveBindNotification(any(), any());
        doThrow(new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "down"))
                .when(service).receiveEcNotification(any(), any());
        callbacks.perform(post("/api/v1/callbacks/lakala/onboard").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FAIL"));
        callbacks.perform(post("/api/v1/callbacks/lakala/onboard/ledger-open").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isNotFound());
        callbacks.perform(post("/api/v1/callbacks/lakala/onboard/bind").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict());
        callbacks.perform(post("/api/v1/callbacks/lakala/onboard/ec").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isServiceUnavailable());
        callbacks.perform(post("/api/v1/callbacks/lakala/onboard").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "a.png", "image/png", PNG);
    }

    private static org.springframework.test.web.servlet.ResultMatcher headerNoStore() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store, private");
    }

    /** The controller stamps a fresh trace id on every command context; everything else is pinned. */
    private static boolean command(CommandContext context) {
        return REQUEST_ID.equals(context.requestId())
                && context.operatorType() == com.petplatform.common.OperatorType.PLATFORM_OPERATOR
                && "9001".equals(context.operatorId()) && "ADMIN_WEB".equals(context.source())
                && context.traceId() != null && !context.traceId().isBlank();
    }

    private static OnboardingView view(String status, long version, String... actions) {
        return new OnboardingView("77", "9001", status, version,
                new MerchantProfileView("PET_HOSPITAL", "示例宠物医院", null, "510104", "天府大道1号",
                        "示例宠物医院", "9111****368", LocalDate.of(2020, 1, 1), LocalDate.of(2040, 1, 1),
                        "*三", "01", "342****8212", LocalDate.of(1999, 5, 29), LocalDate.of(2039, 5, 29),
                        "138****1234", "*四", "139****0000"),
                new com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileView("58", "*三",
                        "6212****0000", "0000", null, null, null, "102100000030", "中国工商银行", "102100099996"),
                new PlatformDefaultsView("CLOUD_DISTRIBUTION", "654", "T+1", "0742", List.of(), "90.50", true),
                Map.of("BANK_CARD", "103"), "201",
                List.of(new OnboardingStepView("EC", "WAITING_SIGN", NOW, null, "待商家法人签署", null)),
                "https://sign.example/x", null, null, null, null, null, null, null, List.of(actions),
                NOW, null, NOW, NOW);
    }

    private static AdminSessionView session(List<String> actions) {
        return new AdminSessionView(
                new AdminSessionPrincipal("ADMIN_WEB", "1001", "9001", 1L),
                NOW.plusHours(1),
                new AdminPermissionSnapshot("9001", "1", NOW, List.of(),
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
