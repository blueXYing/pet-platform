package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.service.api.dto.ServiceCategoryTypes.CreateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.DisableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.EnableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminItem;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminListQuery;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminPage;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryResult;
import com.petplatform.service.api.dto.ServiceCategoryTypes.UpdateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization;
import com.petplatform.service.biz.apiimpl.ServiceCategoryAdminQueryApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceCategoryCommandApiImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Admin service category dictionary management (CCR-SVC-CAT-001 B-SVC-02, contract 74 §4).
 * ADMIN_WEB Bearer is enforced by AdminBearerAuthenticationFilter; every route re-proves
 * {@code service.category.read}/{@code service.category.manage} against the live session
 * snapshot, and the service layer re-proves them in the admin kernel (collection entry check for
 * lists, transactional check for writes). Writes require {@code X-Request-Id}; the service module
 * owns idempotency, version CAS and append-only audit. Icon URLs follow scheme A (no multipart
 * upload pipeline here).
 */
@RestController
@RequestMapping(
        value = "/api/v1/admin/service-categories",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.service.command", name = "enabled", havingValue = "true")
public final class AdminServiceCategoryController {

    private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final ServiceCategoryCommandApiImpl commands;
    private final ServiceCategoryAdminQueryApiImpl queries;

    public AdminServiceCategoryController(
            ServiceCategoryCommandApiImpl commands, ServiceCategoryAdminQueryApiImpl queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    // ------------------------------------------------------------------ reads

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            HttpServletRequest req) {
        onlyParameters(req, "page", "pageSize", "status", "keyword");
        if (status != null && !STATUSES.contains(status)) throw invalid();
        AdminSessionView session = admin(req, "service.category.read");
        ServiceCategoryAdminPage result =
                queries.list(
                        new ServiceCategoryAdminListQuery(
                                page,
                                pageSize,
                                status,
                                keyword,
                                serviceAuthorization(session),
                                adminQuery(req, session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(AdminServiceCategoryController::item).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return ok(data, req);
    }

    // ------------------------------------------------------------------ writes

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> create(@RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        JsonNode body = root(json);
        AdminSessionView session = admin(req, "service.category.manage");
        ServiceCategoryResult result =
                commands.create(
                        new CreateServiceCategoryCommand(
                                code(text(body, "categoryCode")),
                                text(body, "categoryName"),
                                optionalText(body, "parentCode"),
                                optionalText(body, "iconUrl"),
                                optionalText(body, "description"),
                                optionalInt(body, "sortNo"),
                                serviceAuthorization(session),
                                adminCommand(req, session)));
        return ok(receipt(result), req);
    }

    @PutMapping(value = "/{categoryCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(
            @PathVariable String categoryCode,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        JsonNode body = root(json);
        AdminSessionView session = admin(req, "service.category.manage");
        ServiceCategoryResult result =
                commands.update(
                        new UpdateServiceCategoryCommand(
                                code(categoryCode),
                                optionalText(body, "categoryName"),
                                optionalText(body, "parentCode"),
                                body.has("parentCode"),
                                optionalText(body, "iconUrl"),
                                body.has("iconUrl"),
                                optionalText(body, "description"),
                                body.has("description"),
                                optionalInt(body, "sortNo"),
                                requiredVersion(body, "expectedVersion"),
                                serviceAuthorization(session),
                                adminCommand(req, session)));
        return ok(receipt(result), req);
    }

    @PostMapping(
            value = "/{categoryCode}/enable",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> enable(
            @PathVariable String categoryCode,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        JsonNode body = root(json);
        AdminSessionView session = admin(req, "service.category.manage");
        ServiceCategoryResult result =
                commands.enable(
                        new EnableServiceCategoryCommand(
                                code(categoryCode),
                                requiredVersion(body, "expectedVersion"),
                                serviceAuthorization(session),
                                adminCommand(req, session)));
        return ok(statusReceipt(result), req);
    }

    @PostMapping(
            value = "/{categoryCode}/disable",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> disable(
            @PathVariable String categoryCode,
            @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        JsonNode body = root(json);
        AdminSessionView session = admin(req, "service.category.manage");
        ServiceCategoryResult result =
                commands.disable(
                        new DisableServiceCategoryCommand(
                                code(categoryCode),
                                requiredVersion(body, "expectedVersion"),
                                serviceAuthorization(session),
                                adminCommand(req, session)));
        return ok(statusReceipt(result), req);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> ok(Object data, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("code", "SUCCESS");
        out.put("message", "ok");
        out.put("data", data);
        out.put("traceId", trace(req));
        return out;
    }

    private static Map<String, Object> item(ServiceCategoryAdminItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categoryId", i.categoryId());
        out.put("categoryCode", i.categoryCode());
        out.put("categoryName", i.categoryName());
        out.put("parentCode", i.parentCode());
        out.put("iconUrl", i.iconUrl());
        out.put("description", i.description());
        out.put("sortNo", i.sortNo());
        out.put("status", i.status());
        out.put("version", i.version());
        out.put("createdAt", time(i.createdAt()));
        out.put("updatedAt", time(i.updatedAt()));
        return out;
    }

    private static Map<String, Object> receipt(ServiceCategoryResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categoryId", r.categoryId());
        out.put("categoryCode", r.categoryCode());
        out.put("status", r.status());
        out.put("version", r.version());
        return out;
    }

    private static Map<String, Object> statusReceipt(ServiceCategoryResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categoryCode", r.categoryCode());
        out.put("status", r.status());
        out.put("version", r.version());
        return out;
    }

    /** Trusted server-resolved session reference for the service-layer kernel re-check. */
    private static ServiceAdminAuthorization serviceAuthorization(AdminSessionView session) {
        return new ServiceAdminAuthorization(
                session.principal().sessionId(), session.principal().sessionGeneration());
    }

    /** Same approved lexicon as the service module: ^[A-Z][A-Z0-9_]{1,31}$. */
    private static String code(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{1,31}")) throw invalid();
        return value;
    }

    private static JsonNode root(String json) {
        try {
            JsonNode root = STRICT.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) throw invalid();
        return node.asString();
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid();
        return node.asString();
    }

    private static Integer optionalInt(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw invalid();
        return node.asInt();
    }

    private static long requiredVersion(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.asLong() < 0) {
            throw invalid();
        }
        return node.asLong();
    }
}
