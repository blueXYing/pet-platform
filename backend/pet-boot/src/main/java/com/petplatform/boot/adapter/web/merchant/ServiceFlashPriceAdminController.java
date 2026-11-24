package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.service.api.command.ServiceFlashPriceAdminApi;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.AdminReviewCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 77 §4 operations flash-price review face (five routes): list plus approve/reject/
 * pause/end. Real ADMIN_WEB session with the new action codes service.flash-price.read /
 * service.flash-price.review; the module revalidates state, window and price transactionally.
 * Operators never change prices — a bad price is rejected for the merchant to adjust.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/service-flash-prices",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet", name = {"service.flash-price.enabled",
        "service.command.enabled"}, havingValue = "true")
public final class ServiceFlashPriceAdminController {

    private static final ObjectReader STRICT =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED", "REJECTED",
            "CANCELED");

    private final ServiceFlashPriceAdminApi flashPrices;

    public ServiceFlashPriceAdminController(ServiceFlashPriceAdminApi flashPrices) {
        this.flashPrices = flashPrices;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    // ------------------------------------------------------------------ routes

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "page", "pageSize");
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 50
                || (status != null && !STATUSES.contains(status))) throw invalid();
        AdminSessionView session = admin(req, "service.flash-price.read");
        FlashPricePage result = flashPrices.list(status, page, pageSize, adminQuery(req, session));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(ServiceFlashPriceAdminController::item).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return ok(data, req);
    }

    @PostMapping(value = "/{flashPriceId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> approve(
            @PathVariable String flashPriceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        return ok(item(flashPrices.approve(review(flashPriceId, json, req))), req);
    }

    @PostMapping(value = "/{flashPriceId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> reject(
            @PathVariable String flashPriceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = admin(req, "service.flash-price.review");
        JsonNode body = readObject(json);
        String reason = body.get("reason") == null || body.get("reason").isNull()
                ? null : body.get("reason").asString();
        return ok(item(flashPrices.reject(new AdminReviewCommand(
                adminCommand(req, session), id(flashPriceId), expectedVersion(body), reason))), req);
    }

    @PostMapping(value = "/{flashPriceId}/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> pause(
            @PathVariable String flashPriceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        return ok(item(flashPrices.pause(review(flashPriceId, json, req))), req);
    }

    @PostMapping(value = "/{flashPriceId}/end", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> end(
            @PathVariable String flashPriceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        return ok(item(flashPrices.end(review(flashPriceId, json, req))), req);
    }

    // ------------------------------------------------------------------ helpers

    private AdminReviewCommand review(String flashPriceId, String json, HttpServletRequest req) {
        AdminSessionView session = admin(req, "service.flash-price.review");
        JsonNode body = readObject(json);
        return new AdminReviewCommand(
                adminCommand(req, session), id(flashPriceId), expectedVersion(body), null);
    }

    /** ADMIN_WEB envelope (HTTP10 §2.8): the uniform "ok" message, code-gated clients. */
    private static Map<String, Object> ok(Object data, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("code", "SUCCESS");
        out.put("message", "ok");
        out.put("data", data);
        out.put("traceId", trace(req));
        return out;
    }

    /** §2.1 wire shape plus the admin-only service/store decorations. */
    private static Map<String, Object> item(FlashPriceItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flashPriceId", i.flashPriceId());
        out.put("serviceId", i.serviceId());
        out.put("merchantId", i.merchantId());
        out.put("storeId", i.storeId());
        out.put("title", i.title());
        out.put("description", i.description());
        out.put("priceMode", i.priceMode());
        out.put("priceModeLabel", null);
        out.put("discountRate", i.discountRate() == null
                ? null : i.discountRate().setScale(1).toPlainString());
        out.put("flashPrice", money(i.flashPrice()));
        out.put("listPriceSnapshot", money(i.listPriceSnapshot()));
        out.put("startAt", time(i.startAt()));
        out.put("endAt", time(i.endAt()));
        out.put("totalQuota", i.totalQuota());
        out.put("perUserLimit", i.perUserLimit());
        out.put("soldCount", i.soldCount());
        out.put("status", i.status());
        out.put("rejectReason", i.rejectReason());
        out.put("version", i.version());
        out.put("participantCount", i.participantCount());
        out.put("createdAt", time(i.createdAt()));
        out.put("updatedAt", time(i.updatedAt()));
        out.put("serviceName", i.serviceName());
        out.put("storeName", i.storeName());
        return out;
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2).toPlainString();
    }

    private static JsonNode readObject(String json) {
        try {
            JsonNode root = STRICT.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static long expectedVersion(JsonNode root) {
        JsonNode node = root.get("expectedVersion");
        if (node == null || node.isNull()) throw invalid();
        if (node.isIntegralNumber()) {
            long value = node.asLong();
            if (value < 0) throw invalid();
            return value;
        }
        if (node.isString()) return version(node.asString());
        throw invalid();
    }
}
