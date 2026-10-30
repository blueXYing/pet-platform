package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.common.ApiResponse;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.MarketingMerchantApi.MerchantScope;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
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

/** Contract-71 merchant-facing marketing/enrollment face. */
@RestController
@ConditionalOnProperty(prefix = "pet",
        name = {"marketing.enabled", "marketing.merchant.http.enabled", "auth.c.enabled"},
        havingValue = "true")
public class MarketingMerchantController {
    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }


    private final MarketingMerchantApi marketing;

    public MarketingMerchantController(MarketingMerchantApi marketing) {
        this.marketing = marketing;
    }

    @GetMapping(value = "/api/v1/merchant/marketing/campaigns",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> openCampaigns(
            @RequestParam String merchantId,
            @RequestParam String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "status", "page", "pageSize");
        if (status != null && !"ENROLLING".equals(status)) throw invalid();
        MiniSessionView session = mini(req);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(merchantId), longId(storeId));
        MarketingViews.Page<MarketingViews.MerchantCampaignView> result = marketing.listOpenCampaigns(
                new MarketingMerchantApi.MerchantCampaignListQuery(scope, page(page), pageSize(pageSize),
                        userQuery(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        data.put("items", result.items().stream().map(row -> {
            Map<String, Object> out = campaign(row.campaign());
            out.put("enrolled", row.enrolled());
            out.put("enrollmentStatus", row.enrollmentStatus());
            return out;
        }).toList());
        return merchantOk(data, req);
    }

    @PostMapping(value = "/api/v1/merchant/marketing/campaigns/{campaignId}/enrollments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> enroll(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(text(body, "merchantId")),
                longId(text(body, "storeId")));
        MarketingViews.Receipt receipt = marketing.enroll(new MarketingMerchantApi.EnrollCommand(
                scope, id(campaignId), optionalText(body, "offerTag"),
                optionalText(body, "offerTitle"), optionalText(body, "offerCopy"),
                body.hasNonNull("materials") ? body.get("materials").toString() : null,
                userCommand(req)));
        return merchantOk(receipt(receipt), req);
    }

    @GetMapping(value = "/api/v1/merchant/marketing/enrollments",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> enrollments(
            @RequestParam String merchantId,
            @RequestParam String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "status", "page", "pageSize");
        MiniSessionView session = mini(req);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(merchantId), longId(storeId));
        MarketingViews.Page<MarketingViews.EnrollmentView> result = marketing.listMyEnrollments(
                new MarketingMerchantApi.MerchantEnrollmentListQuery(scope, status, page(page),
                        pageSize(pageSize), userQuery(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        data.put("items", result.items().stream().map(MarketingMerchantController::enrollment).toList());
        return merchantOk(data, req);
    }

    @PostMapping(value = "/api/v1/merchant/marketing/enrollments/{enrollmentId}/withdraw",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> withdraw(@PathVariable String enrollmentId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(text(body, "merchantId")),
                longId(text(body, "storeId")));
        MarketingViews.Receipt receipt = marketing.withdraw(new MarketingMerchantApi.WithdrawCommand(
                id(enrollmentId), longValue(body, "expectedVersion"), scope, userCommand(req)));
        return merchantOk(receipt(receipt), req);
    }

    @GetMapping(value = "/api/v1/merchant/marketing/my-campaigns",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> myCampaigns(
            @RequestParam String merchantId,
            @RequestParam String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "status", "page", "pageSize");
        MiniSessionView session = mini(req);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(merchantId), longId(storeId));
        MarketingViews.Page<MarketingViews.CampaignCard> result = marketing.listMyCampaigns(
                new MarketingMerchantApi.MerchantMyCampaignListQuery(scope, status, page(page),
                        pageSize(pageSize), userQuery(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        data.put("items", result.items().stream().map(MarketingMerchantController::campaign).toList());
        return merchantOk(data, req);
    }

    @PostMapping(value = "/api/v1/merchant/marketing/my-campaigns",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createMyCampaign(@RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(text(body, "merchantId")),
                longId(text(body, "storeId")));
        MarketingViews.Receipt receipt = marketing.createMyCampaign(
                new MarketingMerchantApi.MerchantCreateCampaignCommand(
                        scope, text(body, "name"), text(body, "title"), optionalText(body, "subtitle"),
                        optionalText(body, "tagCode"), optionalText(body, "tagName"),
                        optionalText(body, "coverUrl"), time(body, "startAt"), time(body, "endAt"),
                        userCommand(req)));
        return merchantOk(receipt(receipt), req);
    }

    @PutMapping(value = "/api/v1/merchant/marketing/my-campaigns/{campaignId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> updateMyCampaign(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(text(body, "merchantId")),
                longId(text(body, "storeId")));
        MarketingViews.Receipt receipt = marketing.updateMyCampaign(
                new MarketingMerchantApi.MerchantUpdateCampaignCommand(
                        id(campaignId), text(body, "name"), text(body, "title"),
                        optionalText(body, "subtitle"), optionalText(body, "tagCode"),
                        optionalText(body, "tagName"), optionalText(body, "coverUrl"),
                        time(body, "startAt"), time(body, "endAt"),
                        longValue(body, "expectedVersion"), scope, userCommand(req)));
        return merchantOk(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/merchant/marketing/my-campaigns/{campaignId}/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> submitMyCampaign(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return myCampaignStatus(campaignId, raw, req, true);
    }

    @PostMapping(value = "/api/v1/merchant/marketing/my-campaigns/{campaignId}/cancel",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> cancelMyCampaign(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        return myCampaignStatus(campaignId, raw, req, false);
    }

    private Map<String, Object> myCampaignStatus(String campaignId, String raw,
            HttpServletRequest req, boolean submit) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        MerchantScope scope = new MerchantScope(longId(session.userId()),
                longId(text(body, "merchantId")),
                longId(text(body, "storeId")));
        MarketingMerchantApi.MerchantCampaignStatusCommand command =
                new MarketingMerchantApi.MerchantCampaignStatusCommand(
                        id(campaignId), longValue(body, "expectedVersion"), scope, userCommand(req));
        MarketingViews.Receipt receipt = submit ? marketing.submitMyCampaign(command)
                : marketing.cancelMyCampaign(command);
        return merchantOk(receipt(receipt), req);
    }

    private static long longId(String value) {
        try {
            return Long.parseLong(id(value));
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    // ---------------------------------------------------------------- wire

    private static Map<String, Object> campaign(MarketingViews.CampaignCard c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", c.campaignId());
        out.put("name", c.name());
        out.put("title", c.title());
        out.put("subtitle", c.subtitle());
        out.put("tagCode", c.tagCode());
        out.put("tagName", c.tagName());
        out.put("coverUrl", c.coverUrl());
        out.put("campaignType", c.campaignType());
        out.put("participationMode", c.participationMode());
        out.put("status", c.status());
        out.put("startAt", time(c.startAt()));
        out.put("endAt", time(c.endAt()));
        out.put("enrollStartAt", time(c.enrollStartAt()));
        out.put("enrollEndAt", time(c.enrollEndAt()));
        out.put("storeCount", c.storeCount());
        out.put("version", Integer.toString(c.version()));
        return out;
    }

    private static Map<String, Object> enrollment(MarketingViews.EnrollmentView e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrollmentId", e.enrollmentId());
        out.put("campaignId", e.campaignId());
        out.put("campaignTitle", e.campaignTitle());
        out.put("merchantId", e.merchantId());
        out.put("storeId", e.storeId());
        out.put("storeName", e.storeName());
        out.put("status", e.status());
        out.put("offerTag", e.offerTag());
        out.put("offerTitle", e.offerTitle());
        out.put("offerCopy", e.offerCopy());
        out.put("materials", e.materialsJson());
        out.put("rejectReason", e.rejectReason());
        out.put("submittedAt", time(e.submittedAt()));
        out.put("decidedAt", time(e.decidedAt()));
        out.put("version", Integer.toString(e.version()));
        return out;
    }

    private static Map<String, Object> receipt(MarketingViews.Receipt r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("status", r.status());
        out.put("version", Long.toString(r.version()));
        out.put("pageVersion", r.pageVersion());
        out.put("occurredAt", time(r.occurredAt()));
        return out;
    }
}
