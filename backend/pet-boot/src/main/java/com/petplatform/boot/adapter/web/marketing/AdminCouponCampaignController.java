package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiResponse;
import com.petplatform.coupon.api.command.AdminCouponCampaignApi;
import com.petplatform.coupon.api.command.AdminCouponCampaignApi.CampaignInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Contract-72 ops coupon campaign face. */
@RestController
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.admin.enabled", "coupon.campaign.enabled",
                "coupon.campaign.admin.http.enabled"},
        havingValue = "true")
public class AdminCouponCampaignController {
    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }


    private final AdminCouponCampaignApi campaigns;

    public AdminCouponCampaignController(AdminCouponCampaignApi campaigns) {
        this.campaigns = campaigns;
    }

    @GetMapping(value = "/api/v1/admin/coupon-campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "page", "pageSize");
        AdminSessionView session = admin(req, "coupon.campaign.read");
        AdminCouponCampaignApi.Page<AdminCouponCampaignApi.CampaignView> result =
                campaigns.listCampaigns(new AdminCouponCampaignApi.ListQuery(status, page(page),
                        pageSize(pageSize), auth(session), adminQuery(req, session)));
        return ok(pageWire(result, AdminCouponCampaignController::campaign), req);
    }

    @PostMapping(value = "/api/v1/admin/coupon-campaigns",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> create(@RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "coupon.campaign.manage");
        AdminCouponCampaignApi.Receipt receipt = campaigns.createCampaign(
                new AdminCouponCampaignApi.CreateCommand(input(body(raw)), auth(session),
                        adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @GetMapping(value = "/api/v1/admin/coupon-campaigns/{campaignId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> detail(@PathVariable String campaignId, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "coupon.campaign.read");
        AdminCouponCampaignApi.CampaignDetail detail = campaigns.getCampaign(
                new AdminCouponCampaignApi.DetailQuery(id(campaignId), auth(session),
                        adminQuery(req, session)));
        Map<String, Object> out = campaign(detail.campaign());
        out.put("ruleJson", detail.ruleJson());
        out.put("claimCount", detail.claimCount());
        return ok(out, req);
    }

    @PutMapping(value = "/api/v1/admin/coupon-campaigns/{campaignId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> update(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "coupon.campaign.manage");
        JsonNode body = body(raw);
        AdminCouponCampaignApi.Receipt receipt = campaigns.updateCampaign(
                new AdminCouponCampaignApi.UpdateCommand(id(campaignId), input(body),
                        longValue(body, "expectedVersion"), auth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/coupon-campaigns/{campaignId}/publish",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> publish(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return status(campaignId, raw, req, "PUBLISH");
    }

    @PostMapping(value = "/api/v1/admin/coupon-campaigns/{campaignId}/pause",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> pause(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return status(campaignId, raw, req, "PAUSE");
    }

    @PostMapping(value = "/api/v1/admin/coupon-campaigns/{campaignId}/end",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> end(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return status(campaignId, raw, req, "END");
    }

    private ApiResponse<Map<String, Object>> status(String campaignId, String raw,
            HttpServletRequest req, String action) {
        noQuery(req);
        AdminSessionView session = admin(req, "coupon.campaign.publish");
        JsonNode body = body(raw);
        AdminCouponCampaignApi.StatusCommand command = new AdminCouponCampaignApi.StatusCommand(
                id(campaignId), longValue(body, "expectedVersion"), auth(session),
                adminCommand(req, session));
        AdminCouponCampaignApi.Receipt receipt = switch (action) {
            case "PUBLISH" -> campaigns.publishCampaign(command);
            case "PAUSE" -> campaigns.pauseCampaign(command);
            case "END" -> campaigns.endCampaign(command);
            default -> throw invalid();
        };
        return ok(receipt(receipt), req);
    }

    @GetMapping(value = "/api/v1/admin/coupon-campaigns/{campaignId}/claims",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> claims(@PathVariable String campaignId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "page", "pageSize");
        AdminSessionView session = admin(req, "coupon.campaign.read");
        AdminCouponCampaignApi.Page<AdminCouponCampaignApi.ClaimView> result = campaigns.listClaims(
                new AdminCouponCampaignApi.ClaimsQuery(id(campaignId), page(page), pageSize(pageSize),
                        auth(session), adminQuery(req, session)));
        return ok(pageWire(result, AdminCouponCampaignController::claim), req);
    }

    // ---------------------------------------------------------------- helpers

    private static AdminCouponCampaignApi.AdminAuthorization auth(AdminSessionView session) {
        return new AdminCouponCampaignApi.AdminAuthorization(
                session.principal().sessionId(), session.principal().sessionGeneration(),
                session.principal().operatorId());
    }

    private static CampaignInput input(JsonNode body) {
        return new CampaignInput(
                text(body, "name"), text(body, "templateId"), text(body, "audienceType"),
                integer(body, "perUserLimit"), integer(body, "totalLimit"),
                decimal(body, "budgetLimit"), time(body, "startAt"), time(body, "endAt"));
    }

    private static Map<String, Object> campaign(AdminCouponCampaignApi.CampaignView c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", c.campaignId());
        out.put("name", c.name());
        out.put("templateId", c.templateId());
        out.put("templateName", c.templateName());
        out.put("amountOff", c.amountOff());
        out.put("thresholdAmount", c.thresholdAmount());
        out.put("typeLabel", c.typeLabel());
        out.put("audienceType", c.audienceType());
        out.put("perUserLimit", c.perUserLimit());
        out.put("totalLimit", c.totalLimit());
        out.put("budgetLimit", c.budgetLimit());
        out.put("issuedCount", c.issuedCount());
        out.put("issuedAmount", c.issuedAmount());
        out.put("startAt", time(c.startAt()));
        out.put("endAt", time(c.endAt()));
        out.put("status", c.status());
        out.put("version", Integer.toString(c.version()));
        return out;
    }

    private static Map<String, Object> claim(AdminCouponCampaignApi.ClaimView c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claimId", c.claimId());
        out.put("userId", c.userId());
        out.put("couponInstanceId", c.couponInstanceId());
        out.put("claimedAt", time(c.claimedAt()));
        return out;
    }

    private static Map<String, Object> receipt(AdminCouponCampaignApi.Receipt r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", r.campaignId());
        out.put("status", r.status());
        out.put("version", Long.toString(r.version()));
        out.put("occurredAt", time(r.occurredAt()));
        return out;
    }

    private static <T> Map<String, Object> pageWire(AdminCouponCampaignApi.Page<T> value,
            java.util.function.Function<T, Map<String, Object>> mapper) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", value.page());
        out.put("pageSize", value.pageSize());
        out.put("total", value.total());
        out.put("items", value.items().stream().map(mapper).toList());
        return out;
    }
}
