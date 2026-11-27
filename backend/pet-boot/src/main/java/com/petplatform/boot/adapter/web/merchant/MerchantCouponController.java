package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.MerchantCouponApi;
import com.petplatform.coupon.api.command.MerchantCouponApi.Input;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 79 section 3 merchant coupon face (seven routes). MINIAPP Bearer is enforced by
 * CBearerSessionFilter; the merchant coordinates ride the query string; writes carry the terminal
 * X-Request-Id and expectedVersion. Owner admission, the state machine and idempotency live in
 * the coupon module — this adapter only translates. There is deliberately no approve route:
 * merchants never publish (79 section 2.4).
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/coupon-campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet", name = {"coupon.merchant.enabled", "auth.c.enabled"},
        havingValue = "true")
public final class MerchantCouponController {

    private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "REJECTED", "CANCELED", "SCHEDULED", "ACTIVE", "PAUSED",
            "ENDED");
    private static final Set<String> INPUT_FIELDS = Set.of(
            "name", "amountOff", "thresholdAmount", "totalLimit", "perUserLimit", "budgetLimit",
            "startAt", "endAt", "description", "expectedVersion");

    private final MerchantCouponApi coupons;

    public MerchantCouponController(MerchantCouponApi coupons) {
        this.coupons = coupons;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    // ------------------------------------------------------------------ reads

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "status", "page", "pageSize");
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 50
                || (status != null && !STATUSES.contains(status))) throw invalid();
        String merchant = id(merchantId);
        String store = id(storeId);
        MiniSessionView session = mini(req);
        MerchantCouponApi.Page result = coupons.list(new MerchantCouponApi.ListQuery(
                merchant, store, status, page, pageSize,
                new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(MerchantCouponController::item).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return envelope(data, req);
    }

    @GetMapping(value = "/overview")
    Map<String, Object> overview(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        String merchant = id(merchantId);
        String store = id(storeId);
        MiniSessionView session = mini(req);
        MerchantCouponApi.Overview overview = coupons.overview(new MerchantCouponApi.ScopeQuery(
                merchant, store, new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalCampaignCount", overview.totalCampaignCount());
        data.put("activeCampaignCount", overview.activeCampaignCount());
        data.put("pendingReviewCount", overview.pendingReviewCount());
        data.put("monthIssuedCount", overview.monthIssuedCount());
        data.put("redemptionRate", overview.redemptionRate().setScale(4).toPlainString());
        data.put("drivenOrderCount", overview.drivenOrderCount());
        return envelope(data, req);
    }

    @GetMapping(value = "/{campaignId}")
    Map<String, Object> get(
            @PathVariable String campaignId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        String merchant = id(merchantId);
        String store = id(storeId);
        MiniSessionView session = mini(req);
        return envelope(item(coupons.get(new MerchantCouponApi.GetQuery(
                id(campaignId), merchant, store,
                new QueryContext(trace(req), OperatorType.USER, session.userId())))), req);
    }

    // ------------------------------------------------------------------ writes

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> create(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        String merchant = id(merchantId);
        String store = id(storeId);
        mini(req);
        JsonNode body = object(json);
        onlyFields(body, INPUT_FIELDS, null);
        return envelope(receipt(coupons.create(new MerchantCouponApi.CreateCommand(
                merchant, store, userCommand(req), input(body)))), req);
    }

    @PutMapping(value = "/{campaignId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(
            @PathVariable String campaignId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        String merchant = id(merchantId);
        String store = id(storeId);
        mini(req);
        JsonNode body = object(json);
        onlyFields(body, INPUT_FIELDS, null);
        return envelope(receipt(coupons.update(new MerchantCouponApi.UpdateCommand(
                id(campaignId), merchant, store, userCommand(req), expectedVersion(body),
                input(body)))), req);
    }

    @PostMapping(value = "/{campaignId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> submit(
            @PathVariable String campaignId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestBody String json,
            HttpServletRequest req) {
        return status(campaignId, merchantId, storeId, json, req, true);
    }

    @PostMapping(value = "/{campaignId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> cancel(
            @PathVariable String campaignId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestBody String json,
            HttpServletRequest req) {
        return status(campaignId, merchantId, storeId, json, req, false);
    }

    private Map<String, Object> status(String campaignId, String merchantId, String storeId,
            String json, HttpServletRequest req, boolean submit) {
        onlyParameters(req, "merchantId", "storeId");
        String merchant = id(merchantId);
        String store = id(storeId);
        mini(req);
        JsonNode body = object(json);
        onlyFields(body, Set.of(), "expectedVersion");
        MerchantCouponApi.StatusCommand command = new MerchantCouponApi.StatusCommand(
                id(campaignId), merchant, store, userCommand(req), expectedVersion(body));
        return envelope(receipt(submit ? coupons.submit(command) : coupons.cancel(command)), req);
    }

    // ------------------------------------------------------------------ helpers

    /** Contract 79 section 2.3 wire shape; money keeps two decimals. */
    private static Map<String, Object> item(MerchantCouponApi.Item i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", i.campaignId());
        out.put("merchantId", i.merchantId());
        out.put("storeId", i.storeId());
        out.put("name", i.name());
        out.put("couponType", i.couponType());
        out.put("amountOff", money(i.amountOff()));
        out.put("thresholdAmount", money(i.thresholdAmount()));
        out.put("typeLabel", i.typeLabel());
        out.put("totalLimit", i.totalLimit());
        out.put("perUserLimit", i.perUserLimit());
        out.put("budgetLimit", money(i.budgetLimit()));
        out.put("issuedCount", i.issuedCount());
        out.put("issuedAmount", money(i.issuedAmount()));
        out.put("startAt", time(i.startAt()));
        out.put("endAt", time(i.endAt()));
        out.put("description", i.description());
        out.put("status", i.status());
        out.put("rejectReason", i.rejectReason());
        out.put("version", i.version());
        out.put("claimedCount", i.claimedCount());
        out.put("usedCount", i.usedCount());
        out.put("drivenOrderCount", i.drivenOrderCount());
        out.put("budgetUsedAmount", money(i.budgetUsedAmount()));
        out.put("submittedAt", time(i.submittedAt()));
        out.put("reviewedAt", time(i.reviewedAt()));
        out.put("createdAt", time(i.createdAt()));
        out.put("updatedAt", time(i.updatedAt()));
        return out;
    }

    private static Map<String, Object> receipt(MerchantCouponApi.Receipt r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", r.campaignId());
        out.put("status", r.status());
        out.put("version", r.version());
        return out;
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2).toPlainString();
    }

    private static JsonNode object(String json) {
        try {
            JsonNode root = STRICT.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    /** Unknown fields are rejected (79 section 3.1); `allowed` lists the optional extras. */
    private static void onlyFields(JsonNode root, Set<String> fields, String allowed) {
        Set<String> known = new java.util.HashSet<>(fields);
        if (allowed != null) known.add(allowed);
        for (String name : root.propertyNames()) {
            if (!known.contains(name)) throw invalid();
        }
    }

    private static Input input(JsonNode root) {
        return new Input(
                text(root, "name"),
                money(root, "amountOff"),
                root.get("thresholdAmount") == null || root.get("thresholdAmount").isNull()
                        ? null : money(root, "thresholdAmount"),
                integer(root, "totalLimit"),
                integer(root, "perUserLimit"),
                money(root, "budgetLimit"),
                timestamp(text(root, "startAt")),
                timestamp(text(root, "endAt")),
                root.get("description") == null || root.get("description").isNull()
                        ? null : text(root, "description"));
    }

    /** String-or-number optimistic version (the wire answers a number; clients round-trip it). */
    private static long expectedVersion(JsonNode root) {
        JsonNode node = root.get("expectedVersion");
        if (node == null || node.isNull()) throw invalid();
        if (node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0) return node.asInt();
        if (node.isString()) return version(node.asText());
        throw invalid();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) throw invalid();
        return node.asText();
    }

    /** Money: a two-decimal string per contract; a JSON number is accepted as well. */
    private static BigDecimal money(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) throw invalid();
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(text(root, field));
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
    }

    private static int integer(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 1) {
            throw invalid();
        }
        return node.asInt();
    }
}
