package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.service.api.command.ServiceFlashPriceMerchantApi;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CancelCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CreateFlashPriceCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.SubmitCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.UpdateFlashPriceCommand;
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
 * Contract 77 §3 merchant flash-price face (five routes). MINIAPP Bearer enforced by
 * CBearerSessionFilter; writes carry the terminal X-Request-Id and expectedVersion. Owner
 * admission (store OWNER only), the state machine, window-overlap serialization and price
 * computation live in the service module — this adapter only translates. There is deliberately
 * no publish/pause/end route: merchants never publish (77 §2.2).
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/services/{serviceId}/flash-prices",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet", name = {"service.flash-price.enabled",
        "service.command.enabled", "auth.c.enabled"}, havingValue = "true")
public final class ServiceFlashPriceMerchantController {

    private static final ObjectReader STRICT =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED", "REJECTED",
            "CANCELED");
    private static final Set<String> PRICE_MODE_LABELS = Set.of("LIMITED_DISCOUNT", "FLASH_SALE");

    private final ServiceFlashPriceMerchantApi flashPrices;

    public ServiceFlashPriceMerchantController(ServiceFlashPriceMerchantApi flashPrices) {
        this.flashPrices = flashPrices;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    // ------------------------------------------------------------------ routes

    @GetMapping
    Map<String, Object> list(
            @PathVariable String serviceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "page", "pageSize");
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 50
                || (status != null && !STATUSES.contains(status))) throw invalid();
        MiniSessionView session = mini(req);
        FlashPricePage result = flashPrices.list(serviceId, status, page, pageSize,
                new com.petplatform.common.QueryContext(
                        trace(req), com.petplatform.common.OperatorType.USER, session.userId()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(ServiceFlashPriceMerchantController::item).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return envelope(data, req);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> create(
            @PathVariable String serviceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = readObject(json);
        CreateFlashPriceCommand command = new CreateFlashPriceCommand(
                userCommand(req), id(serviceId),
                optionalText(body, "title"),
                optionalText(body, "description"),
                optionalPriceModeLabel(body),
                rate(body),
                parseTime(body, "startAt"),
                parseTime(body, "endAt"),
                optionalInt(body, "totalQuota"),
                optionalInt(body, "perUserLimit"));
        return envelope(item(flashPrices.create(command)), req);
    }

    @PutMapping(value = "/{flashPriceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(
            @PathVariable String serviceId,
            @PathVariable String flashPriceId,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = readObject(json);
        UpdateFlashPriceCommand command = new UpdateFlashPriceCommand(
                userCommand(req), id(serviceId), id(flashPriceId), expectedVersion(body),
                optionalText(body, "title"),
                optionalText(body, "description"),
                optionalPriceModeLabel(body),
                rate(body),
                parseTime(body, "startAt"),
                parseTime(body, "endAt"),
                optionalInt(body, "totalQuota"),
                optionalInt(body, "perUserLimit"));
        return envelope(item(flashPrices.update(command)), req);
    }

    @PostMapping(value = "/{flashPriceId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> submit(
            @PathVariable String serviceId,
            @PathVariable String flashPriceId,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = readObject(json);
        return envelope(item(flashPrices.submit(new SubmitCommand(
                userCommand(req), id(serviceId), id(flashPriceId), expectedVersion(body)))), req);
    }

    @PostMapping(value = "/{flashPriceId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> cancel(
            @PathVariable String serviceId,
            @PathVariable String flashPriceId,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = readObject(json);
        return envelope(item(flashPrices.cancel(new CancelCommand(
                userCommand(req), id(serviceId), id(flashPriceId), expectedVersion(body)))), req);
    }

    // ------------------------------------------------------------------ helpers

    /** Contract 77 §2.1 wire shape; money keeps two decimals, the rate one decimal. */
    private static Map<String, Object> item(FlashPriceItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flashPriceId", i.flashPriceId());
        out.put("serviceId", i.serviceId());
        out.put("merchantId", i.merchantId());
        out.put("storeId", i.storeId());
        out.put("title", i.title());
        out.put("description", i.description());
        out.put("priceMode", i.priceMode());
        out.put("priceModeLabel", null); // display-only input tag; never persisted (77 §3.2)
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

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString() || node.asString().isBlank()) throw invalid();
        return node.asString();
    }

    /** Display tag only: LIMITED_DISCOUNT / FLASH_SALE; anything else is 400 (77 §3.2). */
    private static String optionalPriceModeLabel(JsonNode root) {
        JsonNode node = root.get("priceModeLabel");
        if (node == null || node.isNull()) return null;
        if (!node.isString() || !PRICE_MODE_LABELS.contains(node.asString())) throw invalid();
        return node.asString();
    }

    /** JSON number in (0,10); scale/position rules are revalidated in the service module. */
    private static BigDecimal rate(JsonNode root) {
        JsonNode node = root.get("discountRate");
        if (node == null || !node.isNumber()) throw invalid();
        return node.decimalValue();
    }

    private static OffsetDateTime parseTime(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString()) throw invalid();
        try {
            return OffsetDateTime.parse(node.asString());
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static Integer optionalInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 1) throw invalid();
        return node.asInt();
    }

    /** String-or-number optimistic version (the wire answers a number; clients round-trip it). */
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
