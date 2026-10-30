package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiResponse;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.MarketingAdminApi.CampaignInput;
import com.petplatform.marketing.api.MarketingAdminApi.CampaignStoreInput;
import com.petplatform.marketing.api.MarketingAdminApi.HomeBlockInput;
import com.petplatform.marketing.api.MarketingAdminApi.PlacementInput;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Contract-71 platform ops marketing center face. */
@RestController
@ConditionalOnProperty(prefix = "pet", name = {"auth.admin.enabled", "marketing.admin.http.enabled"},
        havingValue = "true")
public class MarketingAdminController {

    private final MarketingAdminApi marketing;
    private final MerchantStoreDisplayApi stores;
    private final ObjectProvider<MerchantApplicationCityCatalog> cities;
    private final String assetPublicBaseUrl;

    public MarketingAdminController(MarketingAdminApi marketing,
            ObjectProvider<MerchantStoreDisplayApi> stores,
            ObjectProvider<MerchantApplicationCityCatalog> cities,
            @Value("${pet.marketing.asset.public-base-url:}") String assetPublicBaseUrl) {
        this.marketing = marketing;
        this.stores = stores.getIfAvailable();
        this.cities = cities;
        this.assetPublicBaseUrl = assetPublicBaseUrl == null ? "" : assetPublicBaseUrl.strip();
    }

    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    // ------------------------------------------------------------------ assets

    @PostMapping(value = "/api/v1/admin/marketing/assets",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> uploadAsset(
            @RequestPart("file") MultipartFile file, HttpServletRequest req) throws java.io.IOException {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.manage");
        if (file == null || file.isEmpty()) throw invalid();
        if (assetPublicBaseUrl.isBlank()) {
            throw new com.petplatform.common.ApiException(
                    com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "素材公开地址未配置");
        }
        MarketingViews.AssetView asset = marketing.uploadAsset(
                new MarketingAdminApi.AssetUploadCommand(file.getBytes(), file.getContentType(),
                        adminAuth(session), adminCommand(req, session)));
        String base = assetPublicBaseUrl.replaceAll("/$", "");
        String url = base + "/api/v1/c/marketing/assets/" + asset.assetId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.assetId());
        out.put("url", url);
        out.put("contentType", asset.contentType());
        out.put("bytes", asset.bytes());
        out.put("width", asset.width());
        out.put("height", asset.height());
        out.put("sha256", asset.sha256());
        out.put("createdAt", time(asset.createdAt()));
        return ok(out, req);
    }

    // ---------------------------------------------------------------- campaigns

    @GetMapping(value = "/api/v1/admin/marketing/campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> listCampaigns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "ownerType", "keyword", "page", "pageSize");
        AdminSessionView session = admin(req, "marketing.campaign.read");
        MarketingViews.Page<MarketingViews.CampaignCard> result = marketing.listCampaigns(
                new MarketingAdminApi.CampaignListQuery(status, ownerType, keyword,
                        page(page), pageSize(pageSize), adminAuth(session), adminQuery(req, session)));
        return ok(pageWire(result, MarketingAdminController::campaign), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> createCampaign(@RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.manage");
        MarketingViews.Receipt receipt = marketing.createCampaign(new MarketingAdminApi.CreateCampaignCommand(
                campaignInput(body(raw)), adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @GetMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> getCampaign(@PathVariable String campaignId, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.read");
        MarketingViews.CampaignDetail detail = marketing.getCampaign(
                new MarketingAdminApi.CampaignQuery(id(campaignId), adminAuth(session),
                        adminQuery(req, session)));
        return ok(detail(detail), req);
    }

    @PutMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> updateCampaign(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.manage");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.updateCampaign(new MarketingAdminApi.UpdateCampaignCommand(
                id(campaignId), campaignInput(body), longValue(body, "expectedVersion"),
                adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/stores",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> replaceStores(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.manage");
        JsonNode body = body(raw);
        List<CampaignStoreInput> list = new ArrayList<>();
        JsonNode storesNode = body.get("stores");
        if (storesNode == null || !storesNode.isArray()) throw invalid();
        for (JsonNode node : storesNode) {
            list.add(new CampaignStoreInput(id(text(node, "storeId")), optionalText(node, "offerTag"),
                    optionalText(node, "offerTitle"), optionalText(node, "offerCopy"),
                    node.hasNonNull("sortWeight") ? integer(node, "sortWeight") : null));
        }
        MarketingViews.Receipt receipt = marketing.replaceStores(new MarketingAdminApi.ReplaceStoresCommand(
                id(campaignId), list, longValue(body, "expectedVersion"),
                adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/page",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> savePage(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.manage");
        JsonNode body = body(raw);
        JsonNode schema = body.get("schema");
        if (schema == null || schema.isNull()) throw invalid();
        MarketingViews.Receipt receipt = marketing.savePage(new MarketingAdminApi.SavePageCommand(
                id(campaignId), schema.toString(), longValue(body, "expectedVersion"),
                adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/publish",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> publish(@PathVariable String campaignId,
            @RequestBody(required = false) String raw, HttpServletRequest req) {
        return campaignStatus(campaignId, raw, req, "publish");
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/pause",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> pause(@PathVariable String campaignId,
            @RequestBody(required = false) String raw, HttpServletRequest req) {
        return campaignStatus(campaignId, raw, req, "pause");
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/end",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> end(@PathVariable String campaignId,
            @RequestBody(required = false) String raw, HttpServletRequest req) {
        return campaignStatus(campaignId, raw, req, "end");
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/cancel",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> cancel(@PathVariable String campaignId,
            @RequestBody(required = false) String raw, HttpServletRequest req) {
        return campaignStatus(campaignId, raw, req, "cancel");
    }

    private ApiResponse<Map<String, Object>> campaignStatus(String campaignId, String raw,
            HttpServletRequest req, String action) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.publish");
        JsonNode body = body(raw);
        MarketingAdminApi.CampaignStatusCommand command = new MarketingAdminApi.CampaignStatusCommand(
                id(campaignId), longValue(body, "expectedVersion"), adminAuth(session),
                adminCommand(req, session));
        MarketingViews.Receipt receipt = switch (action) {
            case "publish" -> marketing.publishCampaign(command);
            case "pause" -> marketing.pauseCampaign(command);
            case "end" -> marketing.endCampaign(command);
            case "cancel" -> marketing.cancelCampaign(command);
            default -> throw invalid();
        };
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/reject",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> reject(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.publish");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.rejectCampaign(new MarketingAdminApi.RejectCampaignCommand(
                id(campaignId), text(body, "reason"), longValue(body, "expectedVersion"),
                adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/page/rollback",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> rollback(@PathVariable String campaignId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.publish");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.rollbackPage(new MarketingAdminApi.RollbackPageCommand(
                id(campaignId), integer(body, "pageVersion"), longValue(body, "expectedVersion"),
                adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @GetMapping(value = "/api/v1/admin/marketing/campaigns/{campaignId}/preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> preview(@PathVariable String campaignId, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.campaign.read");
        MarketingViews.PagePreview preview = marketing.previewCampaign(
                new MarketingAdminApi.CampaignQuery(id(campaignId), adminAuth(session),
                        adminQuery(req, session)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", preview.campaignId());
        out.put("draftVersion", preview.draftVersion());
        out.put("publishedVersion", preview.publishedVersion());
        out.put("draftSchemaJson", preview.draftSchemaJson());
        out.put("stores", preview.stores().stream().map(MarketingAdminController::storeLink).toList());
        return ok(out, req);
    }

    // ---------------------------------------------------------------- placements

    @GetMapping(value = "/api/v1/admin/marketing/placements", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> listPlacements(
            @RequestParam(required = false) String positionCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "positionCode", "status", "page", "pageSize");
        AdminSessionView session = admin(req, "marketing.placement.read");
        MarketingViews.Page<MarketingViews.PlacementRow> result = marketing.listPlacements(
                new MarketingAdminApi.PlacementListQuery(positionCode, status, page(page),
                        pageSize(pageSize), adminAuth(session), adminQuery(req, session)));
        return ok(pageWire(result, MarketingAdminController::placement), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/placements",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> createPlacement(@RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.placement.manage");
        MarketingViews.Receipt receipt = marketing.createPlacement(new MarketingAdminApi.CreatePlacementCommand(
                placementInput(body(raw)), adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PutMapping(value = "/api/v1/admin/marketing/placements/{placementId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> updatePlacement(@PathVariable String placementId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.placement.manage");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.updatePlacement(new MarketingAdminApi.UpdatePlacementCommand(
                id(placementId), placementInput(body), longValue(body, "expectedVersion"),
                adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/placements/{placementId}/enable",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> enablePlacement(@PathVariable String placementId,
            @RequestBody String raw, HttpServletRequest req) {
        return placementStatus(placementId, raw, req, true);
    }

    @PostMapping(value = "/api/v1/admin/marketing/placements/{placementId}/disable",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> disablePlacement(@PathVariable String placementId,
            @RequestBody String raw, HttpServletRequest req) {
        return placementStatus(placementId, raw, req, false);
    }

    private ApiResponse<Map<String, Object>> placementStatus(String placementId, String raw,
            HttpServletRequest req, boolean enable) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.placement.manage");
        JsonNode body = body(raw);
        MarketingAdminApi.PlacementStatusCommand command = new MarketingAdminApi.PlacementStatusCommand(
                id(placementId), longValue(body, "expectedVersion"), adminAuth(session),
                adminCommand(req, session));
        MarketingViews.Receipt receipt = enable ? marketing.enablePlacement(command)
                : marketing.disablePlacement(command);
        return ok(receipt(receipt), req);
    }

    // ---------------------------------------------------------------- home blocks

    @GetMapping(value = "/api/v1/admin/marketing/home-blocks",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> listHomeBlocks(
            @RequestParam(required = false) String positionCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "positionCode", "status", "page", "pageSize");
        AdminSessionView session = admin(req, "marketing.placement.read");
        MarketingViews.Page<MarketingViews.HomeBlockRow> result = marketing.listHomeBlocks(
                new MarketingAdminApi.HomeBlockListQuery(positionCode, status, page(page),
                        pageSize(pageSize), adminAuth(session), adminQuery(req, session)));
        return ok(pageWire(result, MarketingAdminController::homeBlock), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/home-blocks",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> createHomeBlock(@RequestBody String raw,
            HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.placement.manage");
        MarketingViews.Receipt receipt = marketing.createHomeBlock(
                new MarketingAdminApi.CreateHomeBlockCommand(homeBlockInput(body(raw)),
                        adminAuth(session), adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PutMapping(value = "/api/v1/admin/marketing/home-blocks/{blockId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> updateHomeBlock(@PathVariable String blockId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.placement.manage");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.updateHomeBlock(
                new MarketingAdminApi.UpdateHomeBlockCommand(id(blockId), homeBlockInput(body),
                        longValue(body, "expectedVersion"), adminAuth(session),
                        adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/home-blocks/{blockId}/enable",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> enableHomeBlock(@PathVariable String blockId,
            @RequestBody String raw, HttpServletRequest req) {
        return homeBlockStatus(blockId, raw, req, true);
    }

    @PostMapping(value = "/api/v1/admin/marketing/home-blocks/{blockId}/disable",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> disableHomeBlock(@PathVariable String blockId,
            @RequestBody String raw, HttpServletRequest req) {
        return homeBlockStatus(blockId, raw, req, false);
    }

    private ApiResponse<Map<String, Object>> homeBlockStatus(String blockId, String raw,
            HttpServletRequest req, boolean enable) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.placement.manage");
        JsonNode body = body(raw);
        MarketingAdminApi.HomeBlockStatusCommand command = new MarketingAdminApi.HomeBlockStatusCommand(
                id(blockId), longValue(body, "expectedVersion"), adminAuth(session),
                adminCommand(req, session));
        MarketingViews.Receipt receipt = enable ? marketing.enableHomeBlock(command)
                : marketing.disableHomeBlock(command);
        return ok(receipt(receipt), req);
    }

    // ---------------------------------------------------------------- enrollments

    @GetMapping(value = "/api/v1/admin/marketing/enrollments", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> listEnrollments(
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "campaignId", "status", "page", "pageSize");
        AdminSessionView session = admin(req, "marketing.enrollment.read");
        MarketingViews.Page<MarketingViews.EnrollmentView> result = marketing.listEnrollments(
                new MarketingAdminApi.EnrollmentListQuery(campaignId, status, page(page),
                        pageSize(pageSize), adminAuth(session), adminQuery(req, session)));
        return ok(pageWire(result, MarketingAdminController::enrollment), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/enrollments/{enrollmentId}/review",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> reviewEnrollment(@PathVariable String enrollmentId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.enrollment.review");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.reviewEnrollment(
                new MarketingAdminApi.ReviewEnrollmentCommand(
                        id(enrollmentId), text(body, "decision"), optionalText(body, "reason"),
                        longValue(body, "expectedVersion"), adminAuth(session),
                        adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    @PostMapping(value = "/api/v1/admin/marketing/enrollments/{enrollmentId}/approve",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> approveEnrollment(@PathVariable String enrollmentId,
            @RequestBody String raw, HttpServletRequest req) {
        return reviewAlias(enrollmentId, raw, req, "APPROVE");
    }

    @PostMapping(value = "/api/v1/admin/marketing/enrollments/{enrollmentId}/reject",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> rejectEnrollment(@PathVariable String enrollmentId,
            @RequestBody String raw, HttpServletRequest req) {
        return reviewAlias(enrollmentId, raw, req, "REJECT");
    }

    private ApiResponse<Map<String, Object>> reviewAlias(String enrollmentId, String raw,
            HttpServletRequest req, String decision) {
        noQuery(req);
        AdminSessionView session = admin(req, "marketing.enrollment.review");
        JsonNode body = body(raw);
        MarketingViews.Receipt receipt = marketing.reviewEnrollment(
                new MarketingAdminApi.ReviewEnrollmentCommand(
                        id(enrollmentId), decision, optionalText(body, "reason"),
                        longValue(body, "expectedVersion"), adminAuth(session),
                        adminCommand(req, session)));
        return ok(receipt(receipt), req);
    }

    // ---------------------------------------------------------------- store picker

    @GetMapping(value = "/api/v1/admin/marketing/stores", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> pickStores(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "keyword", "cityCode", "page", "pageSize");
        if (cityCode != null && !cityCode.matches("[a-z][a-z0-9_-]{0,31}")) throw invalid();
        AdminSessionView session = admin(req, "marketing.campaign.read");
        if (stores == null) throw new com.petplatform.common.ApiException(
                com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE, "门店事实不可用");
        List<String> cityCodes = new ArrayList<>();
        if (cityCode != null) {
            cityCodes.add(cityCode);
        } else {
            MerchantApplicationCityCatalog catalog = cities.getIfAvailable();
            if (catalog == null) throw new com.petplatform.common.ApiException(
                    com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录不可用");
            catalog.list().forEach(city -> cityCodes.add(city.code()));
        }
        MerchantStoreDisplayPageDTO result = stores.pageDisplayStores(
                new MerchantStoreDisplayPageQuery(cityCodes, keyword, page(page), pageSize(pageSize),
                        userlessQuery(req), false));
        List<MerchantStoreDisplayDTO> visible = result.items().stream()
                .filter(dto -> storeInScope(session, dto)).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", visible.size());
        data.put("items", visible.stream().map(MarketingAdminController::storeOption).toList());
        return ok(data, req);
    }

    /** Mirrors contract 71 §6: the store picker never widens the operator's data scope. */
    private static boolean storeInScope(AdminSessionView session, MerchantStoreDisplayDTO dto) {
        var scope = session.permissions().dataScope();
        return switch (scope.mode()) {
            case "ALL" -> true;
            case "CITY" -> dto.cityCode() != null && scope.cityCodes().contains(dto.cityCode());
            case "MERCHANT" -> scope.merchantIds().contains(dto.merchantId());
            default -> false;
        };
    }

    private static com.petplatform.common.QueryContext userlessQuery(HttpServletRequest req) {
        return new com.petplatform.common.QueryContext(trace(req),
                com.petplatform.common.OperatorType.PLATFORM_OPERATOR, "0");
    }

    // ---------------------------------------------------------------- input parsing

    private static CampaignInput campaignInput(JsonNode body) {
        return new CampaignInput(
                text(body, "name"), text(body, "campaignType"), text(body, "participationMode"),
                text(body, "title"), optionalText(body, "subtitle"), optionalText(body, "tagCode"),
                optionalText(body, "tagName"), optionalText(body, "coverUrl"),
                stringList(body.get("cityCodes")), stringList(body.get("serviceCategoryCodes")),
                time(body, "startAt"), time(body, "endAt"),
                optionalTime(body, "enrollStartAt"), optionalTime(body, "enrollEndAt"),
                optionalInt(body, "enrollmentQuota"), optionalText(body, "couponCampaignId"),
                optionalInt(body, "sortWeight"));
    }

    private static PlacementInput placementInput(JsonNode body) {
        return new PlacementInput(
                text(body, "positionCode"), text(body, "campaignId"), optionalText(body, "cityCode"),
                optionalText(body, "titleOverride"), optionalText(body, "subtitleOverride"),
                optionalText(body, "imageUrlOverride"), optionalText(body, "templateCode"),
                body.hasNonNull("sortWeight") ? integer(body, "sortWeight") : null,
                time(body, "startAt"), time(body, "endAt"));
    }

    private static HomeBlockInput homeBlockInput(JsonNode body) {
        return new HomeBlockInput(
                text(body, "blockCode"), text(body, "positionCode"), text(body, "templateCode"),
                text(body, "title"), optionalText(body, "subtitle"), optionalText(body, "tagName"),
                optionalText(body, "imageUrl"), text(body, "targetType"),
                optionalText(body, "targetValue"), optionalText(body, "cityCode"),
                optionalText(body, "sponsorMerchantId"), optionalText(body, "sponsorStoreId"),
                body.hasNonNull("sortWeight") ? integer(body, "sortWeight") : null,
                time(body, "startAt"), time(body, "endAt"));
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw invalid();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw invalid();
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private static Integer optionalInt(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw invalid();
        return value.asInt();
    }

    private static OffsetDateTime optionalTime(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        try {
            return OffsetDateTime.parse(value.asText());
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    // ---------------------------------------------------------------- wire

    private static Map<String, Object> campaign(MarketingViews.CampaignCard c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", c.campaignId());
        out.put("campaignCode", c.campaignCode());
        out.put("name", c.name());
        out.put("ownerType", c.ownerType());
        out.put("campaignType", c.campaignType());
        out.put("participationMode", c.participationMode());
        out.put("status", c.status());
        out.put("title", c.title());
        out.put("subtitle", c.subtitle());
        out.put("tagCode", c.tagCode());
        out.put("tagName", c.tagName());
        out.put("coverUrl", c.coverUrl());
        out.put("cityCodes", c.cityCodes());
        out.put("serviceCategoryCodes", c.serviceCategoryCodes());
        out.put("startAt", time(c.startAt()));
        out.put("endAt", time(c.endAt()));
        out.put("enrollStartAt", time(c.enrollStartAt()));
        out.put("enrollEndAt", time(c.enrollEndAt()));
        out.put("enrollmentQuota", c.enrollmentQuota());
        out.put("couponCampaignId", c.couponCampaignId());
        out.put("pageVersion", c.pageVersion());
        out.put("storeCount", c.storeCount());
        out.put("sortWeight", c.sortWeight());
        out.put("version", Integer.toString(c.version()));
        out.put("createdAt", time(c.createdAt()));
        out.put("updatedAt", time(c.updatedAt()));
        return out;
    }

    private static Map<String, Object> detail(MarketingViews.CampaignDetail d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaign", campaign(d.campaign()));
        out.put("stores", d.stores().stream().map(MarketingAdminController::storeLink).toList());
        out.put("pageSchema", d.pageSchemaJson());
        out.put("publishedPageSchema", d.publishedPageSchemaJson());
        out.put("pageVersions", d.pageVersions().stream().map(v -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", v.version());
            row.put("publishedAt", time(v.publishedAt()));
            return row;
        }).toList());
        return out;
    }

    private static Map<String, Object> storeLink(MarketingViews.CampaignStoreLink s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", s.storeId());
        out.put("merchantId", s.merchantId());
        out.put("source", s.source());
        out.put("status", s.status());
        out.put("offerTag", s.offerTag());
        out.put("offerTitle", s.offerTitle());
        out.put("offerCopy", s.offerCopy());
        out.put("sortWeight", s.sortWeight());
        return out;
    }

    private static Map<String, Object> placement(MarketingViews.PlacementRow p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placementId", p.placementId());
        out.put("positionCode", p.positionCode());
        out.put("campaignId", p.campaignId());
        out.put("campaignTitle", p.campaignTitle());
        out.put("cityCode", p.cityCode());
        out.put("titleOverride", p.titleOverride());
        out.put("subtitleOverride", p.subtitleOverride());
        out.put("imageUrlOverride", p.imageUrlOverride());
        out.put("templateCode", p.templateCode());
        out.put("sortWeight", p.sortWeight());
        out.put("startAt", time(p.startAt()));
        out.put("endAt", time(p.endAt()));
        out.put("status", p.status());
        out.put("version", Integer.toString(p.version()));
        return out;
    }

    private static Map<String, Object> homeBlock(MarketingViews.HomeBlockRow b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blockId", b.blockId());
        out.put("blockCode", b.blockCode());
        out.put("positionCode", b.positionCode());
        out.put("templateCode", b.templateCode());
        out.put("title", b.title());
        out.put("subtitle", b.subtitle());
        out.put("tagName", b.tagName());
        out.put("imageUrl", b.imageUrl());
        out.put("targetType", b.targetType());
        out.put("targetValue", b.targetValue());
        out.put("cityCode", b.cityCode());
        out.put("sponsorMerchantId", b.sponsorMerchantId());
        out.put("sponsorStoreId", b.sponsorStoreId());
        out.put("sortWeight", b.sortWeight());
        out.put("startAt", time(b.startAt()));
        out.put("endAt", time(b.endAt()));
        out.put("status", b.status());
        out.put("version", Integer.toString(b.version()));
        return out;
    }

    private static Map<String, Object> enrollment(MarketingViews.EnrollmentView e) {        Map<String, Object> out = new LinkedHashMap<>();
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
        out.put("decidedBy", e.decidedBy());
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

    private static Map<String, Object> storeOption(MerchantStoreDisplayDTO dto) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", dto.storeId());
        out.put("merchantId", dto.merchantId());
        out.put("storeName", dto.storeName());
        out.put("merchantName", dto.merchantName());
        out.put("cityCode", dto.cityCode());
        out.put("address", dto.address());
        if (dto.promoLevel() != null || dto.promoTitle() != null || dto.promoText() != null) {
            Map<String, Object> promotion = new LinkedHashMap<>();
            promotion.put("level", dto.promoLevel());
            promotion.put("title", dto.promoTitle());
            promotion.put("text", dto.promoText());
            out.put("promotion", promotion);
        } else {
            out.put("promotion", null);
        }
        return out;
    }

    private static <T> Map<String, Object> pageWire(MarketingViews.Page<T> value,
            java.util.function.Function<T, Map<String, Object>> mapper) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", value.page());
        out.put("pageSize", value.pageSize());
        out.put("total", value.total());
        out.put("items", value.items().stream().map(mapper).toList());
        return out;
    }
}
