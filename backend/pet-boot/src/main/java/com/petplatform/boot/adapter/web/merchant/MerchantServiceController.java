package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryItem;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementItem;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementPage;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemResult;
import com.petplatform.service.api.dto.ServiceWriteTypes.CreateServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemFields;
import com.petplatform.service.api.dto.ServiceWriteTypes.SubmitServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.TakeServiceOfflineCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.UpdateServiceItemCommand;
import com.petplatform.service.biz.apiimpl.ServiceCommandApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceManagementQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Merchant workbench service management (HTTP10 §4.10, proposal v0.2 approved): six routes plus
 * the ENABLED category dictionary read. MINIAPP Bearer enforced by CBearerSessionFilter; writes
 * require the terminal X-Request-Id and stay idempotent across replays (23号). The owner gate and
 * state machine live in the service module — this adapter only translates.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.service.command", name = "enabled", havingValue = "true")
public final class MerchantServiceController {

    private static final ObjectReader STRICT =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> STATUSES =
            Set.of("DRAFT", "REVIEWING", "ACTIVE", "OFFLINE", "REJECTED");

    private final ServiceCommandApiImpl commands;
    private final ServiceManagementQueryApiImpl workbench;

    public MerchantServiceController(
            ServiceCommandApiImpl commands, ServiceManagementQueryApiImpl workbench) {
        this.commands = commands;
        this.workbench = workbench;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    // ------------------------------------------------------------------ reads

    @GetMapping("/services")
    Map<String, Object> list(
            @RequestParam String merchantId,
            @RequestParam String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "status", "page", "pageSize");
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 100
                || (status != null && !STATUSES.contains(status))) throw invalid();
        MiniSessionView session = mini(req);
        ServiceManagementPage result =
                workbench.listManaged(
                        new ServiceManagementListQuery(
                                id(merchantId),
                                id(storeId),
                                status,
                                page,
                                pageSize,
                                new QueryContext(
                                        trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(MerchantServiceController::item).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return envelope(data, req);
    }

    @GetMapping("/services/{serviceId}")
    Map<String, Object> get(
            @PathVariable String serviceId,
            @RequestParam String merchantId,
            @RequestParam String storeId,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        MiniSessionView session = mini(req);
        ServiceManagementItem result =
                workbench.getManaged(
                        new ServiceManagementDetailQuery(
                                id(serviceId),
                                id(merchantId),
                                id(storeId),
                                new QueryContext(
                                        trace(req), OperatorType.USER, session.userId())));
        return envelope(item(result), req);
    }

    @GetMapping("/service-categories")
    Map<String, Object> categories(HttpServletRequest req) {
        onlyParameters(req);
        MiniSessionView session = mini(req);
        List<ServiceCategoryItem> items =
                workbench.listEnabledCategories(
                        new com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryQuery(
                                new QueryContext(
                                        trace(req), OperatorType.USER, session.userId())))
                        .items();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "items",
                items.stream()
                        .map(c -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("categoryId", c.categoryId());
                            row.put("categoryName", c.categoryName());
                            row.put("sortNo", c.sortNo());
                            return row;
                        })
                        .toList());
        return envelope(data, req);
    }

    // ------------------------------------------------------------------ writes

    @PostMapping(value = "/services", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> create(
            @RequestBody String json, HttpServletRequest req, HttpServletResponse response) {
        onlyParameters(req);
        mini(req);
        CreateServiceItemCommand command =
                new CreateServiceItemCommand(
                        id(text(json, "merchantId")),
                        id(text(json, "storeId")),
                        fields(json),
                        userCommand(req));
        ServiceItemResult[] out = new ServiceItemResult[1];
        boolean created = commands.createDraftOutcome(command, out);
        response.setStatus(created ? 201 : 200);
        return envelope(receipt(out[0]), req);
    }

    @PutMapping(value = "/services/{serviceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(
            @PathVariable String serviceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        UpdateServiceItemCommand command =
                new UpdateServiceItemCommand(id(serviceId), fields(json),
                        version(text(json, "expectedVersion")), userCommand(req));
        return envelope(receipt(commands.update(command)), req);
    }

    @PostMapping(value = "/services/{serviceId}/online", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> online(
            @PathVariable String serviceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        SubmitServiceItemCommand command =
                new SubmitServiceItemCommand(
                        id(serviceId), version(text(json, "expectedVersion")), userCommand(req));
        return envelope(receipt(commands.submitForReview(command)), req);
    }

    @PostMapping(value = "/services/{serviceId}/offline", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> offline(
            @PathVariable String serviceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        TakeServiceOfflineCommand command =
                new TakeServiceOfflineCommand(
                        id(serviceId), version(text(json, "expectedVersion")), userCommand(req));
        return envelope(receipt(commands.takeOffline(command)), req);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> receipt(ServiceItemResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", r.serviceId());
        out.put("merchantId", r.merchantId());
        out.put("storeId", r.storeId());
        out.put("status", r.status());
        out.put("version", Long.toString(r.version()));
        if (r.decisionId() != null) out.put("decisionId", r.decisionId());
        if (r.actionId() != null) out.put("actionId", r.actionId());
        return out;
    }

    private static Map<String, Object> item(ServiceManagementItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", i.serviceId());
        out.put("merchantId", i.merchantId());
        out.put("storeId", i.storeId());
        out.put("serviceName", i.serviceName());
        out.put("categoryId", i.categoryId());
        out.put("categoryName", i.categoryName());
        out.put("status", i.status());
        out.put("price", money(i.price()));
        out.put("listPrice", money(i.listPrice()));
        out.put("durationMinutes", i.durationMinutes());
        out.put("fulfillmentType", i.fulfillmentType());
        out.put("coverAssetId", i.coverAssetId());
        out.put(
                "applicablePetTypes",
                i.applicablePetTypes() == null ? null : List.of(i.applicablePetTypes().split(",")));
        out.put("staffRequirement", i.staffRequirement());
        out.put("verificationRequired", i.verificationRequired());
        out.put("description", i.description());
        out.put("aftersaleNote", i.aftersaleNote());
        out.put("remark", i.remark());
        out.put("submissionNo", i.submissionNo());
        out.put("submittedAt", time(i.submittedAt()));
        out.put("version", Long.toString(i.version()));
        out.put("updatedAt", time(i.updatedAt()));
        out.put("latestRejection", decision(i.latestRejection()));
        return out;
    }

    private static Map<String, Object> decision(
            com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDecisionView d) {
        if (d == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisionId", d.decisionId());
        out.put("submissionNo", d.submissionNo());
        out.put("decisionType", d.decisionType());
        out.put("opinion", d.opinion());
        out.put("decidedAt", time(d.decidedAt()));
        return out;
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** Strict two-decimal money lexicon: 128 / 128.0 / 128.00 pass, 128.000 fails (10号 §2.7). */
    private static BigDecimal amount(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString()
                || !node.asString().matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,2})?")) throw invalid();
        return new BigDecimal(node.asString());
    }

    private static ServiceItemFields fields(String json) {
        JsonNode root = readObject(json);
        JsonNode pets = root.get("applicablePetTypes");
        List<String> petTypes;
        if (pets == null || pets.isNull()) {
            petTypes = null;
        } else {
            if (!pets.isArray()) throw invalid();
            java.util.ArrayList<String> types = new java.util.ArrayList<>();
            for (int i = 0; i < pets.size(); i++) types.add(requireText(pets.get(i), "applicablePetTypes"));
            petTypes = types;
        }
        return new ServiceItemFields(
                optionalText(root, "serviceName", 50),
                optionalId(root, "categoryId"),
                optionalEnum(root, "fulfillmentType", Set.of("IN_STORE", "PICKUP_DELIVERY")),
                amount(root, "price"),
                amount(root, "listPrice"),
                optionalInt(root, "durationMinutes"),
                optionalId(root, "coverAssetId"),
                petTypes,
                optionalText(root, "staffRequirement", 200),
                optionalBool(root, "verificationRequired"),
                optionalText(root, "description", 1000),
                optionalText(root, "aftersaleNote", 500),
                optionalText(root, "remark", 500));
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

    private static String text(String json, String field) {
        JsonNode node = readObject(json).get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) throw invalid();
        return node.asString();
    }

    private static String requireText(JsonNode value, String field) {
        if (value == null || !value.isString() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }

    private static String optionalText(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString() || node.asString().isBlank()
                || node.asString().length() > maxLength) throw invalid();
        return node.asString();
    }

    private static String optionalId(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid();
        return id(node.asString());
    }

    private static String optionalEnum(JsonNode root, String field, Set<String> values) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString() || !values.contains(node.asString())) throw invalid();
        return node.asString();
    }

    private static Integer optionalInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber() || !node.canConvertToInt() || node.asInt() < 1) throw invalid();
        return node.asInt();
    }

    private static Boolean optionalBool(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isBoolean()) throw invalid();
        return node.asBoolean();
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
