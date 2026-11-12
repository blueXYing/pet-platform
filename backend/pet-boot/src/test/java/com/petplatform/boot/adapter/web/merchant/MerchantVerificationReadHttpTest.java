package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.MerchantOrderQueryApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import com.petplatform.verification.api.query.MerchantVerificationReadApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.StoreVerificationListQuery;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.VerificationRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * CCR-M-VERIFY-RECORD-001 transport shape over mocked kernels (direct controller calls, the
 * MerchantStoreProfilePromoLevelHttpTest style): OWNER/STAFF row assembly with the CCR §9
 * display pair, two-decimal amounts, the same-coordinate order-card assembly, null degradation
 * for missing summaries, strict parameter discipline (400 family) and the family 403
 * passthrough. The real-kernel OWNER guard chain, MySQL filters and cross-store 403 live in
 * the acceptance suite.
 */
class MerchantVerificationReadHttpTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-12T01:02:03.400Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-11-12T05:06:07.800Z");

    /** Page: one OWNER row (card present) + one STAFF row (order summary missing → nulls). */
    private static final class FixedVerifications implements MerchantVerificationReadApi {
        StoreVerificationListQuery captured;

        @Override
        public PageResult<VerificationRow> listStoreVerifications(StoreVerificationListQuery query) {
            captured = query;
            return new PageResult<>(List.of(
                new VerificationRow("9501", "9601", "710302",
                    "MERCHANT_STAFF", "STAFF", "710350", "李四", "SCAN", T2),
                new VerificationRow("9502", "9602", "710302",
                    "USER", "OWNER", null, "店长", "MANUAL", T1)),
                2, 1, 20);
        }
    }

    /** Order cards: only the OWNER row's order resolves; the STAFF row's order misses. */
    private static final class FixedOrders implements MerchantOrderQueryApi {
        String capturedMerchant;
        String capturedStore;
        Collection<String> capturedIds;
        QueryContext capturedContext;

        @Override
        public PageResult<MerchantOrderQueryApi.Summary> listStoreOrders(
                MerchantOrderQueryApi.StoreOrderListQuery query) {
            throw new UnsupportedOperationException("not used by this face");
        }

        @Override
        public Map<String, MerchantOrderQueryApi.Summary> storeOrderSummariesByIds(
                String merchantId, String storeId, Collection<String> orderIds, QueryContext context) {
            capturedMerchant = merchantId;
            capturedStore = storeId;
            capturedIds = orderIds;
            capturedContext = context;
            Map<String, MerchantOrderQueryApi.Summary> cards = new LinkedHashMap<>();
            cards.put("9602", new MerchantOrderQueryApi.Summary("9602", "9702", "COMPLETED",
                new BigDecimal("128.5"), null, null, null,
                "小狗主人", "https://wx.example/avatar.png", "洗护精致套餐",
                null, null, null));
            return cards;
        }
    }

    private static MockHttpServletRequest request(String... parameters) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
            "/api/v1/merchant/verifications");
        request.setAttribute(CBearerSessionFilter.VIEW,
            new MiniSessionView("session-1", "710300", Instant.now(), "138****0300", "ACTIVE"));
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            request.addParameter(parameters[i], parameters[i + 1]);
        }
        return request;
    }

    @Test
    void assemblesOwnerAndStaffRowsWithCardFactsAndDisplayPair() {
        var verifications = new FixedVerifications();
        var orders = new FixedOrders();
        var controller = new MerchantVerificationReadController(verifications, orders);
        Map<String, Object> envelope = controller.list("710301", "710302", null, null, null,
            null, null, request());
        assertEquals(Boolean.TRUE, envelope.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");

        // Kernel query carries the session subject and the raw coordinates.
        assertEquals("710301", verifications.captured.merchantId());
        assertEquals("710302", verifications.captured.storeId());
        assertEquals(OperatorType.USER, verifications.captured.context().operatorType());
        assertEquals("710300", verifications.captured.context().operatorId());
        // Card assembly runs at the same coordinates with the page's own order ids.
        assertEquals("710301", orders.capturedMerchant);
        assertEquals("710302", orders.capturedStore);
        assertEquals(java.util.Set.of("9601", "9602"), java.util.Set.copyOf(orders.capturedIds));
        assertEquals("710300", orders.capturedContext.operatorId());

        assertEquals(1, data.get("page"));
        assertEquals(20, data.get("pageSize"));
        assertEquals(2L, data.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertEquals(2, items.size());

        Map<String, Object> staff = items.get(0);
        // CCR §4: exactly the nine wire keys — no operatorStaffId, no operatorId, no phone.
        assertEquals(java.util.Set.of("verificationId", "orderId", "serviceName", "payAmount",
            "customerNickname", "customerAvatarUrl", "verifiedAt", "operatorKind",
            "operatorName"), staff.keySet());
        assertEquals("9501", staff.get("verificationId"));
        assertEquals("9601", staff.get("orderId"));
        assertEquals("STAFF", staff.get("operatorKind"));
        assertEquals("李四", staff.get("operatorName"));
        assertEquals("2026-11-12T05:06:07.800Z", staff.get("verifiedAt"));
        // Missing order summary degrades to null — never fabricated.
        assertNull(staff.get("serviceName"));
        assertNull(staff.get("payAmount"));
        assertNull(staff.get("customerNickname"));
        assertNull(staff.get("customerAvatarUrl"));

        Map<String, Object> owner = items.get(1);
        assertEquals("OWNER", owner.get("operatorKind"));
        assertEquals("店长", owner.get("operatorName"));
        assertEquals("洗护精致套餐", owner.get("serviceName"));
        // 11号 DecimalAmountOutput: always two decimals.
        assertEquals("128.50", owner.get("payAmount"));
        assertEquals("小狗主人", owner.get("customerNickname"));
        assertEquals("https://wx.example/avatar.png", owner.get("customerAvatarUrl"));
        assertEquals("2026-11-12T01:02:03.400Z", owner.get("verifiedAt"));
    }

    @Test
    void forbiddenFromTheKernelPassesThroughUnmapped() {
        var denied = new MerchantVerificationReadApi() {
            @Override
            public PageResult<VerificationRow> listStoreVerifications(StoreVerificationListQuery query) {
                throw new ApiException(CommonApiCodes.FORBIDDEN, "Existing order authority unavailable");
            }
        };
        var controller = new MerchantVerificationReadController(denied, new FixedOrders());
        ApiException error = assertThrows(ApiException.class,
            () -> controller.list("710301", "710302", null, null, null, null, null, request()));
        assertEquals(CommonApiCodes.FORBIDDEN, error.code());
    }

    @Test
    void strictParameterDiscipline() {
        var controller = new MerchantVerificationReadController(new FixedVerifications(), new FixedOrders());
        // Unknown query parameter.
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "who", "1"));
        // Missing / malformed coordinates.
        assertInvalid(controller, request("storeId", "710302"));
        assertInvalid(controller, request("merchantId", "710301", "storeId", "abc"));
        // Malformed optional orderId.
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "orderId", "9x1"));
        // Non-millisecond / non-ISO timestamps.
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302",
            "verifiedFrom", "2026-11-12T00:00:00Z"));
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302",
            "verifiedFrom", "not-a-time", "verifiedTo", "2026-11-13T00:00:00.000Z"));
        // page/pageSize bounds: 0, >10000, non-digit, >100.
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "page", "0"));
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "page", "10001"));
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "page", "x"));
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "pageSize", "101"));
        assertInvalid(controller, request("merchantId", "710301", "storeId", "710302", "pageSize", "0"));
        // Kernel-side range discipline passes through the same 400 family (63号 pairing rule).
        MerchantVerificationReadApi unpaired = new MerchantVerificationReadApi() {
            @Override
            public PageResult<VerificationRow> listStoreVerifications(StoreVerificationListQuery query) {
                throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "verified range must be paired");
            }
        };
        var strict = new MerchantVerificationReadController(unpaired, new FixedOrders());
        ApiException error = assertThrows(ApiException.class, () -> strict.list("710301", "710302",
            null, "2026-11-12T00:00:00.000Z", null, null, null, request()));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, error.code());
    }

    @Test
    void responsesAreNeverCached() {
        var controller = new MerchantVerificationReadController(new FixedVerifications(), new FixedOrders());
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.cache(response);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    private static void assertInvalid(MerchantVerificationReadController controller,
            MockHttpServletRequest request) {
        ApiException error = assertThrows(ApiException.class, () -> controller.list(
            request.getParameter("merchantId"), request.getParameter("storeId"),
            request.getParameter("orderId"), request.getParameter("verifiedFrom"),
            request.getParameter("verifiedTo"), request.getParameter("page"),
            request.getParameter("pageSize"), request));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, error.code());
    }
}
