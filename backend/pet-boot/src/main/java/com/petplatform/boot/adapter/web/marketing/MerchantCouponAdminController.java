package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiResponse;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi.AdminAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Contract 79 section 4 operator review of merchant coupons (six routes). The path prefix is the
 * existing {@code /api/v1/admin/coupon-campaigns} security-chain prefix; the literal
 * {@code merchant} segment always wins over the {@code {campaignId}} route of the platform face.
 * Action codes are the existing coupon.campaign.read / coupon.campaign.publish. Operators never
 * edit coupon content here: approve, reject, pause and end only.
 */
@RestController
@ConditionalOnProperty(prefix = "pet", name = {"coupon.merchant.enabled", "auth.admin.enabled"},
        havingValue = "true")
public class MerchantCouponAdminController {

    private static final String BASE = "/api/v1/admin/coupon-campaigns/merchant";
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "REJECTED", "CANCELED", "SCHEDULED", "ACTIVE", "PAUSED",
            "ENDED");

    private final MerchantCouponAdminApi coupons;

    public MerchantCouponAdminController(MerchantCouponAdminApi coupons) {
        this.coupons = coupons;
    }

    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping(value = BASE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "merchantId", "page", "pageSize");
        if (status != null && !STATUSES.contains(status)) throw invalid();
        AdminSessionView session = admin(req, "coupon.campaign.read");
        MerchantCouponAdminApi.Page result = coupons.list(new MerchantCouponAdminApi.ListQuery(
                status, merchantId == null || merchantId.isBlank() ? null : id(merchantId),
                page(page), pageSize(pageSize), auth(session), adminQuery(req, session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(MerchantCouponAdminController::item).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return ok(data, req);
    }

    @GetMapping(value = BASE + "/{campaignId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> detail(@PathVariable String campaignId, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "coupon.campaign.read");
        return ok(item(coupons.get(new MerchantCouponAdminApi.GetQuery(
                id(campaignId), auth(session), adminQuery(req, session)))), req);
    }

    @PostMapping(value = BASE + "/{campaignId}/approve",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> approve(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return review(campaignId, raw, req, "APPROVE");
    }

    @PostMapping(value = BASE + "/{campaignId}/reject",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> reject(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return review(campaignId, raw, req, "REJECT");
    }

    @PostMapping(value = BASE + "/{campaignId}/pause",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> pause(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return review(campaignId, raw, req, "PAUSE");
    }

    @PostMapping(value = BASE + "/{campaignId}/end",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> end(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return review(campaignId, raw, req, "END");
    }

    private ApiResponse<Map<String, Object>> review(String campaignId, String raw,
            HttpServletRequest req, String action) {
        noQuery(req);
        AdminSessionView session = admin(req, "coupon.campaign.publish");
        JsonNode body = body(raw);
        for (String name : body.propertyNames()) {
            if (!"expectedVersion".equals(name) && !"reason".equals(name)) throw invalid();
        }
        String reason = body.get("reason") == null || body.get("reason").isNull()
                ? null : text(body, "reason");
        MerchantCouponAdminApi.ReviewCommand command = new MerchantCouponAdminApi.ReviewCommand(
                id(campaignId), adminCommand(req, session), auth(session),
                longValue(body, "expectedVersion"), reason);
        MerchantCouponAdminApi.Receipt receipt = switch (action) {
            case "APPROVE" -> coupons.approve(command);
            case "REJECT" -> coupons.reject(command);
            case "PAUSE" -> coupons.pause(command);
            default -> coupons.end(command);
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", receipt.campaignId());
        out.put("status", receipt.status());
        out.put("version", receipt.version());
        return ok(out, req);
    }

    private static AdminAuthorization auth(AdminSessionView session) {
        return new AdminAuthorization(session.principal().sessionId(),
                session.principal().sessionGeneration(), session.principal().operatorId());
    }

    /** Contract 79 section 4 wire shape; money keeps two decimals. */
    private static Map<String, Object> item(MerchantCouponAdminApi.Item i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", i.campaignId());
        out.put("merchantId", i.merchantId());
        out.put("merchantName", i.merchantName());
        out.put("storeId", i.storeId());
        out.put("storeName", i.storeName());
        out.put("templateId", i.templateId());
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
        out.put("reviewedByType", i.reviewedByType());
        out.put("createdAt", time(i.createdAt()));
        out.put("updatedAt", time(i.updatedAt()));
        return out;
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2).toPlainString();
    }
}
