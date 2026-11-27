package com.petplatform.boot.adapter.web.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.petplatform.boot.adapter.web.merchant.MerchantCouponController;
import com.petplatform.boot.adapter.web.merchant.MerchantHttpExceptionHandler;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi;
import com.petplatform.coupon.api.command.MerchantCouponApi;
import com.petplatform.coupon.api.error.MerchantCouponApiCodes;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi.CouponCampaignView;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Contract 79 HTTP tests (section 3 merchant face, section 4 operator review, section 5 C list):
 * switch-driven wiring, strict query/body parsing, and the registry status of every coupon code
 * on each face. The kernels behind the apis are mocked here; their behavior is covered by the
 * coupon module's MySQL tests.
 */
class MerchantCouponHttpControllerTest {

    private static final String REQ = "33333333-3333-4333-8333-333333333333";
    private static final String AUTH = "Authorization";
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = START.plusDays(30);
    private static final String MERCHANT = "/api/v1/merchant/coupon-campaigns?merchantId=101&storeId=901";
    private static final String REVIEW = "/api/v1/admin/coupon-campaigns/merchant";

    @Test
    void controllersFollowTheMerchantCouponSwitch() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(MerchantCouponController.class,
                        MerchantCouponAdminController.class, CMerchantCouponCampaignController.class)
                .withBean(MerchantCouponApi.class, () -> mock(MerchantCouponApi.class))
                .withBean(MerchantCouponAdminApi.class, () -> mock(MerchantCouponAdminApi.class))
                .withBean(CouponCampaignQueryApi.class, () -> mock(CouponCampaignQueryApi.class));

        // Bare application.yml (isolated tests): nothing is registered, whatever the base switches are.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(MerchantCouponController.class);
            assertThat(ctx).doesNotHaveBean(MerchantCouponAdminController.class);
            assertThat(ctx).doesNotHaveBean(CMerchantCouponCampaignController.class);
        });
        runner.withPropertyValues("pet.auth.c.enabled=true", "pet.auth.admin.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(MerchantCouponController.class);
            assertThat(ctx).doesNotHaveBean(MerchantCouponAdminController.class);
            assertThat(ctx).doesNotHaveBean(CMerchantCouponCampaignController.class);
        });

        // Switch on: each face additionally follows its own base switch.
        runner.withPropertyValues("pet.coupon.merchant.enabled=true", "pet.auth.c.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MerchantCouponController.class);
            assertThat(ctx).hasSingleBean(CMerchantCouponCampaignController.class);
            assertThat(ctx).doesNotHaveBean(MerchantCouponAdminController.class);
        });
        runner.withPropertyValues("pet.coupon.merchant.enabled=true", "pet.auth.admin.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(MerchantCouponAdminController.class);
                    assertThat(ctx).doesNotHaveBean(MerchantCouponController.class);
                    assertThat(ctx).doesNotHaveBean(CMerchantCouponCampaignController.class);
                });
    }

    @Test
    void merchantFaceParsesStrictlyAndMapsDomainCodes() throws Exception {
        MerchantCouponApi api = mock(MerchantCouponApi.class);
        when(api.list(any())).thenReturn(new MerchantCouponApi.Page(List.of(item()), 1, 20, 1));
        when(api.create(any())).thenReturn(new MerchantCouponApi.Receipt("8001", "DRAFT", 1));
        when(api.overview(any())).thenReturn(new MerchantCouponApi.Overview(
                3, 1, 1, 12, new BigDecimal("0.6250"), 7));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MerchantCouponController(api))
                .setControllerAdvice(new MerchantHttpExceptionHandler())
                .build();

        // 1. No session -> 401.
        mvc.perform(get(MERCHANT)).andExpect(status().isUnauthorized());

        // 2. List: envelope shape, two-decimal money, coordinates and USER context passed down.
        mvc.perform(get(MERCHANT + "&status=ACTIVE&page=1&pageSize=20")
                        .requestAttr(CBearerSessionFilter.VIEW, owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].campaignId").value("8001"))
                .andExpect(jsonPath("$.data.items[0].couponType").value("THRESHOLD"))
                .andExpect(jsonPath("$.data.items[0].amountOff").value("10.00"))
                .andExpect(jsonPath("$.data.items[0].thresholdAmount").value("100.00"))
                .andExpect(jsonPath("$.data.items[0].budgetUsedAmount").value("30.00"))
                .andExpect(jsonPath("$.data.items[0].version").value(3));
        ArgumentCaptor<MerchantCouponApi.ListQuery> list = ArgumentCaptor.forClass(MerchantCouponApi.ListQuery.class);
        verify(api).list(list.capture());
        assertThat(list.getValue().merchantId()).isEqualTo("101");
        assertThat(list.getValue().storeId()).isEqualTo("901");
        assertThat(list.getValue().status()).isEqualTo("ACTIVE");
        assertThat(list.getValue().context().operatorType()).isEqualTo(OperatorType.USER);
        assertThat(list.getValue().context().operatorId()).isEqualTo("201");

        // 3. Unknown query parameter / unknown status -> 400.
        mvc.perform(get(MERCHANT + "&foo=1").requestAttr(CBearerSessionFilter.VIEW, owner()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonApiCodes.INVALID_ARGUMENT));
        mvc.perform(get(MERCHANT + "&status=LIVE").requestAttr(CBearerSessionFilter.VIEW, owner()))
                .andExpect(status().isBadRequest());

        // 4. Overview: the rate stays a four-decimal string.
        mvc.perform(get("/api/v1/merchant/coupon-campaigns/overview?merchantId=101&storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthIssuedCount").value(12))
                .andExpect(jsonPath("$.data.redemptionRate").value("0.6250"))
                .andExpect(jsonPath("$.data.drivenOrderCount").value(7));

        // 5. Create: a server-owned field is rejected before the kernel is reached.
        mvc.perform(post(MERCHANT).requestAttr(CBearerSessionFilter.VIEW, owner())
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content(input("\"status\":\"ACTIVE\",")))
                .andExpect(status().isBadRequest());
        verify(api, never()).create(any());

        // 6. Create without X-Request-Id -> 400.
        mvc.perform(post(MERCHANT).requestAttr(CBearerSessionFilter.VIEW, owner())
                        .contentType(MediaType.APPLICATION_JSON).content(input("")))
                .andExpect(status().isBadRequest());
        verify(api, never()).create(any());

        // 7. Create with a numeric amount is accepted and reaches the kernel with the coordinates.
        mvc.perform(post(MERCHANT).requestAttr(CBearerSessionFilter.VIEW, owner())
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content(input("").replace("\"amountOff\":\"10.00\"", "\"amountOff\":10.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value("8001"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1));
        ArgumentCaptor<MerchantCouponApi.CreateCommand> create =
                ArgumentCaptor.forClass(MerchantCouponApi.CreateCommand.class);
        verify(api).create(create.capture());
        assertThat(create.getValue().merchantId()).isEqualTo("101");
        assertThat(create.getValue().storeId()).isEqualTo("901");
        assertThat(create.getValue().input().amountOff()).isEqualByComparingTo("10.50");
        assertThat(create.getValue().input().thresholdAmount()).isEqualByComparingTo("100.00");
        assertThat(create.getValue().input().startAt()).isEqualTo(START);
        assertThat(create.getValue().context().requestId()).isEqualTo(REQ);
        assertThat(create.getValue().context().operatorType()).isEqualTo(OperatorType.USER);

        // 8. Domain codes carry their registry statuses; non-owner admission reads as plain 404.
        //    doThrow(...).when(...): re-stubbing a method that already throws must not invoke it.
        doThrow(new ApiException(MerchantCouponApiCodes.NOT_FOUND, "x")).when(api).get(any());
        mvc.perform(get("/api/v1/merchant/coupon-campaigns/8001?merchantId=101&storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MERCHANT_COUPON_NOT_FOUND"));
        doThrow(new ApiException(MerchantCouponApiCodes.STATE_NOT_ALLOWED, "x")).when(api).submit(any());
        mvc.perform(post("/api/v1/merchant/coupon-campaigns/8001/submit?merchantId=101&storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner())
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":\"2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_COUPON_STATE_NOT_ALLOWED"));
        doThrow(new ApiException(MerchantCouponApiCodes.NAME_DUPLICATE, "x")).when(api).update(any());
        mvc.perform(put("/api/v1/merchant/coupon-campaigns/8001?merchantId=101&storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner())
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content(input("").replace("{", "{\"expectedVersion\":2,")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_COUPON_NAME_DUPLICATE"));
        doThrow(new ApiException(MerchantCouponApiCodes.INVALID, "x")).when(api).cancel(any());
        mvc.perform(post("/api/v1/merchant/coupon-campaigns/8001/cancel?merchantId=101&storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner())
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MERCHANT_COUPON_INVALID"));
        doThrow(new ApiException(CommonApiCodes.NOT_FOUND, "x")).when(api).submit(any());
        mvc.perform(post("/api/v1/merchant/coupon-campaigns/8001/submit?merchantId=101&storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner())
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(CommonApiCodes.NOT_FOUND));
    }

    @Test
    void operatorReviewEnforcesActionCodesAndCarriesTheAuthorizationReference() throws Exception {
        MerchantCouponAdminApi api = mock(MerchantCouponAdminApi.class);
        when(api.list(any())).thenReturn(new MerchantCouponAdminApi.Page(List.of(adminItem()), 1, 20, 1));
        when(api.approve(any())).thenReturn(new MerchantCouponAdminApi.Receipt("8001", "ACTIVE", 3));
        when(api.reject(any())).thenReturn(new MerchantCouponAdminApi.Receipt("8001", "REJECTED", 3));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MerchantCouponAdminController(api))
                .setControllerAdvice(new MarketingHttpExceptionHandler())
                .build();
        AdminSessionView reader = admin("9001", List.of("coupon.campaign.read"));
        AdminSessionView publisher = admin("9002", List.of("coupon.campaign.read", "coupon.campaign.publish"));

        // 1. No session -> 401; the review list is read for coupon.campaign.read.
        mvc.perform(get(REVIEW)).andExpect(status().isUnauthorized());
        mvc.perform(get(REVIEW + "?status=PENDING_REVIEW").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].merchantName").value("商家甲"))
                .andExpect(jsonPath("$.data.items[0].storeName").value("门店甲"))
                .andExpect(jsonPath("$.data.items[0].issuedAmount").value("20.00"))
                .andExpect(jsonPath("$.data.items[0].budgetLimit").value("2000.00"));
        mvc.perform(get(REVIEW + "?status=LIVE").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, reader))
                .andExpect(status().isBadRequest());

        // 2. Read-only operator cannot review; the kernel is never reached.
        mvc.perform(post(REVIEW + "/8001/approve").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, reader)
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isForbidden());
        verify(api, never()).approve(any());

        // 3. Unknown body field / missing X-Request-Id -> 400.
        mvc.perform(post(REVIEW + "/8001/approve").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, publisher)
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"force\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(REVIEW + "/8001/approve").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, publisher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest());
        verify(api, never()).approve(any());

        // 4. Approve: receipt plus the authorization reference of the acting session.
        mvc.perform(post(REVIEW + "/8001/approve").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, publisher)
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(3));
        ArgumentCaptor<MerchantCouponAdminApi.ReviewCommand> approve =
                ArgumentCaptor.forClass(MerchantCouponAdminApi.ReviewCommand.class);
        verify(api).approve(approve.capture());
        assertThat(approve.getValue().campaignId()).isEqualTo("8001");
        assertThat(approve.getValue().expectedVersion()).isEqualTo(2L);
        assertThat(approve.getValue().reason()).isNull();
        assertThat(approve.getValue().authorization().operatorId()).isEqualTo("9002");
        assertThat(approve.getValue().authorization().sessionId()).isEqualTo("1001");
        assertThat(approve.getValue().authorization().sessionGeneration()).isEqualTo(1L);
        assertThat(approve.getValue().context().operatorType()).isEqualTo(OperatorType.PLATFORM_OPERATOR);

        // 5. Reject carries the reason; a state conflict answers 409.
        mvc.perform(post(REVIEW + "/8001/reject").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, publisher)
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"reason\":\"优惠力度过大\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        ArgumentCaptor<MerchantCouponAdminApi.ReviewCommand> reject =
                ArgumentCaptor.forClass(MerchantCouponAdminApi.ReviewCommand.class);
        verify(api).reject(reject.capture());
        assertThat(reject.getValue().reason()).isEqualTo("优惠力度过大");
        when(api.end(any())).thenThrow(new ApiException(MerchantCouponApiCodes.STATE_NOT_ALLOWED, "x"));
        mvc.perform(post(REVIEW + "/8001/end").header(AUTH, "Bearer t")
                        .requestAttr(AdminBearerAuthenticationFilter.VIEW, publisher)
                        .header("X-Request-Id", REQ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_COUPON_STATE_NOT_ALLOWED"));
    }

    @Test
    void consumerListAllowsAnonymousReadsAndMarksTheFundingType() throws Exception {
        CouponCampaignQueryApi queries = mock(CouponCampaignQueryApi.class);
        when(queries.listMerchantCampaigns(any(), any(), any())).thenReturn(List.of(view()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CMerchantCouponCampaignController(queries))
                .setControllerAdvice(new MarketingHttpExceptionHandler())
                .build();

        // 1. Anonymous read: no userId, hence no operator type on the context.
        mvc.perform(get("/api/v1/c/coupon-campaigns?storeId=901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].fundingType").value("MERCHANT"))
                .andExpect(jsonPath("$.data.items[0].merchantName").value("商家甲"))
                .andExpect(jsonPath("$.data.items[0].canClaim").value(true));
        verify(queries).listMerchantCampaigns(any(), isNull(), any());

        // 2. Logged-in read passes the user id; unknown query / missing store -> 400.
        mvc.perform(get("/api/v1/c/coupon-campaigns?storeId=901")
                        .requestAttr(CBearerSessionFilter.VIEW, owner()))
                .andExpect(status().isOk());
        ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
        verify(queries, times(2)).listMerchantCampaigns(eq("901"), users.capture(), any());
        assertThat(users.getAllValues()).containsExactly(null, "201");
        mvc.perform(get("/api/v1/c/coupon-campaigns?storeId=901&foo=1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/c/coupon-campaigns")).andExpect(status().isBadRequest());

        // 3. Unknown store keeps the contract-72 not-found code.
        doThrow(new ApiException("COUPON_CAMPAIGN_NOT_FOUND", "券活动不存在"))
                .when(queries).listMerchantCampaigns(any(), any(), any());
        mvc.perform(get("/api/v1/c/coupon-campaigns?storeId=902"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON_CAMPAIGN_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ fixtures

    private static String input(String extra) {
        return "{\"name\":\"新客立减\",\"amountOff\":\"10.00\",\"thresholdAmount\":\"100.00\","
                + "\"totalLimit\":100,\"perUserLimit\":1,\"budgetLimit\":\"2000.00\","
                + "\"startAt\":\"2026-12-01T00:00:00.000Z\",\"endAt\":\"2026-12-31T00:00:00.000Z\""
                + extra + "}";
    }

    private static MiniSessionView owner() {
        return new MiniSessionView("7001", "201", Instant.parse("2099-01-01T00:00:00Z"), null, "ACTIVE");
    }

    private static AdminSessionView admin(String operatorId, List<String> actions) {
        OffsetDateTime now = OffsetDateTime.of(2026, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        return new AdminSessionView(
                new AdminSessionPrincipal("ADMIN_WEB", "1001", operatorId, 1L),
                now.plusHours(1),
                new AdminPermissionSnapshot(operatorId, "1", now, List.of(),
                        new AdminDataScope("ALL", List.of(), List.of()), actions));
    }

    private static MerchantCouponApi.Item item() {
        return new MerchantCouponApi.Item("8001", "101", "901", "新客立减20元券", "THRESHOLD",
                new BigDecimal("10.00"), new BigDecimal("100.00"), "满减券", 100, 1,
                new BigDecimal("2000.00"), 2, new BigDecimal("20.00"), START, END, "说明", "ACTIVE",
                null, 3L, 2, 3, 2, new BigDecimal("30.00"), START, START, START, START);
    }

    private static MerchantCouponAdminApi.Item adminItem() {
        return new MerchantCouponAdminApi.Item("8001", "101", "商家甲", "901", "门店甲", "7001",
                "新客立减20元券", "THRESHOLD", new BigDecimal("10.00"), new BigDecimal("100.00"),
                "满减券", 100, 1, new BigDecimal("2000.00"), 2, new BigDecimal("20.00"), START, END,
                "说明", "ACTIVE", null, 3L, 2, 3, 2, new BigDecimal("30.00"), START, START,
                "PLATFORM_OPERATOR", START, START);
    }

    private static CouponCampaignView view() {
        return new CouponCampaignView("8001", "新客立减20元券", "7001", new BigDecimal("10.00"),
                new BigDecimal("100.00"), "满减券", 98, 1, false, true, null, START, END, "ACTIVE",
                "MERCHANT", "101", "商家甲");
    }
}
