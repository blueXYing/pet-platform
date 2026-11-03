package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.CouponClaimApi;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract-72 C-side coupon campaign detail + active claim. */
@RestController
@ConditionalOnProperty(prefix = "pet",
        name = {"coupon.campaign.enabled", "coupon.campaign.c.http.enabled", "auth.c.enabled"},
        havingValue = "true")
public class CCouponCampaignController {
    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }


    private final CouponCampaignQueryApi queries;
    private final CouponClaimApi claims;

    public CCouponCampaignController(CouponCampaignQueryApi queries, CouponClaimApi claims) {
        this.queries = queries;
        this.claims = claims;
    }

    @GetMapping(value = "/api/v1/c/coupon-campaigns/{campaignId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> detail(@PathVariable String campaignId, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = optionalSession(req);
        String userId = session == null ? null : session.userId();
        CouponCampaignQueryApi.CouponCampaignView view = queries.getCampaign(
                id(campaignId), userId, new QueryContext(trace(req),
                        userId == null ? null : OperatorType.USER, userId));
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
        return ok(out, req);
    }

    @PostMapping(value = "/api/v1/c/coupon-campaigns/{campaignId}/claims",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> claim(@PathVariable String campaignId, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        CouponClaimApi.ClaimReceipt receipt = claims.claim(new CouponClaimApi.ClaimCommand(
                id(campaignId), session.userId(), requestId(req), trace(req)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", receipt.campaignId());
        out.put("claimId", receipt.claimId());
        out.put("couponInstanceId", receipt.couponInstanceId());
        out.put("amountOff", receipt.amountOff() == null ? null : receipt.amountOff().toPlainString());
        out.put("expireAt", time(receipt.expireAt()));
        out.put("claimedAt", time(receipt.claimedAt()));
        return ok(out, req);
    }

    private static MiniSessionView optionalSession(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        return view instanceof MiniSessionView session ? session : null;
    }
}
