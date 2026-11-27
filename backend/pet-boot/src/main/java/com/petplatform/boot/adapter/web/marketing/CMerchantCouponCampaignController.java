package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi.CouponCampaignView;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract 79 section 5: claimable merchant coupons of the store's merchant. Registered only
 * while both the C session base and the merchant coupon switch are on, so a switched-off
 * deployment does not expose the route at all. Claiming itself stays on the contract-72 route.
 */
@RestController
@ConditionalOnProperty(prefix = "pet",
        name = {"coupon.merchant.enabled", "auth.c.enabled"}, havingValue = "true")
public class CMerchantCouponCampaignController {

    private final CouponCampaignQueryApi queries;

    public CMerchantCouponCampaignController(CouponCampaignQueryApi queries) {
        this.queries = queries;
    }

    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping(value = "/api/v1/c/coupon-campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String storeId, HttpServletRequest req) {
        onlyParameters(req, "storeId");
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        String userId = view instanceof MiniSessionView session ? session.userId() : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", queries.listMerchantCampaigns(id(storeId), userId,
                        new QueryContext(trace(req), userId == null ? null : OperatorType.USER, userId))
                .stream().map(CMerchantCouponCampaignController::item).toList());
        return ok(data, req);
    }

    /** Same shape as the contract-72 section 5.1 detail plus the contract-79 members. */
    private static Map<String, Object> item(CouponCampaignView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", view.campaignId());
        out.put("name", view.name());
        out.put("templateId", view.templateId());
        out.put("amountOff", view.amountOff() == null ? null : view.amountOff().toPlainString());
        out.put("thresholdAmount", view.thresholdAmount() == null ? null
                : view.thresholdAmount().toPlainString());
        out.put("typeLabel", view.typeLabel());
        out.put("remaining", view.remaining());
        out.put("perUserLimit", view.perUserLimit());
        out.put("claimed", view.claimed());
        out.put("canClaim", view.canClaim());
        out.put("reason", view.reason());
        out.put("startAt", time(view.startAt()));
        out.put("endAt", time(view.endAt()));
        out.put("status", view.status());
        out.put("fundingType", view.fundingType());
        out.put("merchantId", view.merchantId());
        out.put("merchantName", view.merchantName());
        return out;
    }
}
