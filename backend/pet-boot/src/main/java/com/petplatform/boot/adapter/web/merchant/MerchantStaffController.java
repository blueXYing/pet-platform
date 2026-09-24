package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.*;
import com.petplatform.merchant.api.query.*;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffApiImpl;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/** Approved owner-only staff profile routes. The blocked disable route is intentionally absent. */
@RestController
@RequestMapping(value = "/api/v1/merchant/staff", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.staff", name = "enabled", havingValue = "true")
public final class MerchantStaffController {
    private static final ObjectReader STRICT = com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private final MerchantStaffApiImpl staff;
    public MerchantStaffController(MerchantStaffApiImpl staff) { this.staff = staff; }

    @ModelAttribute void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    Map<String,Object> list(@RequestParam String merchantId, @RequestParam String storeId,
            @RequestParam(required=false) String page, @RequestParam(required=false) String pageSize,
            @RequestParam(required=false) String employmentStatus,
            @RequestParam(required=false) String serviceEnabled, HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "page", "pageSize", "employmentStatus", "serviceEnabled");
        MerchantStaffPageDTO result = staff.listStaff(new MerchantStaffListQuery(id(merchantId), id(storeId),
                page(page, 1, 10_000), page(pageSize, 20, 100), employmentStatus,
                serviceEnabled == null ? null : boolQuery(serviceEnabled), userQuery(req)));
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("items", result.items());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return envelope(data, req);
    }

    @GetMapping("/{staffId}")
    Map<String,Object> get(@PathVariable String staffId, @RequestParam String merchantId,
            @RequestParam String storeId, HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        return envelope(staff.getStaff(new MerchantStaffQuery(id(merchantId), id(storeId), id(staffId),
                userQuery(req))), req);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> create(@RequestBody String json, HttpServletRequest req, HttpServletResponse response) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId","storeId","staffName","phone","employmentStatus","serviceEnabled"));
        MerchantStaffCommandResult result = staff.createStaff(new CreateMerchantStaffCommand(
                id(requiredText(body,"merchantId")), id(requiredText(body,"storeId")),
                requiredText(body,"staffName"), optionalPhone(body), requiredText(body,"employmentStatus"),
                requiredBoolean(body,"serviceEnabled"), userCommand(req)));
        response.setStatus(result.created() ? 201 : 200);
        return envelope(result.staff(), req);
    }

    @PutMapping(value="/{staffId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> update(@PathVariable String staffId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId","storeId","staffName","phone","expectedVersion"));
        MerchantStaffCommandResult result = staff.updateStaff(new UpdateMerchantStaffCommand(
                id(requiredText(body,"merchantId")), id(requiredText(body,"storeId")), id(staffId),
                requiredText(body,"staffName"), optionalPhone(body),
                Long.toString(version(requiredText(body,"expectedVersion"))), userCommand(req)));
        return envelope(result.staff(), req);
    }

    @PostMapping(value="/{staffId}/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> enable(@PathVariable String staffId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId","storeId","expectedVersion"));
        MerchantStaffCommandResult result = staff.enableStaff(new EnableMerchantStaffCommand(
                id(requiredText(body,"merchantId")), id(requiredText(body,"storeId")), id(staffId),
                Long.toString(version(requiredText(body,"expectedVersion"))), userCommand(req)));
        return envelope(result.staff(), req);
    }

    private static JsonNode body(String json, Set<String> allowed) {
        try {
            JsonNode root = STRICT.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            Iterator<String> fields = root.propertyNames().iterator();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
            return root;
        } catch (RuntimeException failure) { throw invalid(); }
    }
    private static String requiredText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }
    private static String optionalPhone(JsonNode body) {
        JsonNode value = body.get("phone");
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.asText();
    }
    private static Boolean requiredBoolean(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }
    private static Boolean boolQuery(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw invalid();
    }
    private static int page(String value, int fallback, int max) {
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]*") || value.length() > 5) throw invalid();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > max) throw invalid();
            return parsed;
        } catch (NumberFormatException failure) { throw invalid(); }
    }
}
