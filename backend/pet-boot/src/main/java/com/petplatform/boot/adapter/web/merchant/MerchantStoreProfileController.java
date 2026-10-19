package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.command.MerchantStoreProfileCommandApi;
import com.petplatform.merchant.api.command.UpdateStoreProfileCommand;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi.StoreProfileQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Store profile routes (contract 27 §6.4 supplement, HTTP10 §4.7): OWNER read + full-replace
 * update with the supplement-23 idempotency header. The strict reader requires every editable
 * key (absent keys cannot say "clear"); explicit JSON null is invalid; text fields clear via
 * the empty string, photoAssetIds/businessHours via [], promoLevel via 0 (=未设置).
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/store-profile", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.store-profile", name = "http.enabled", havingValue = "true")
public final class MerchantStoreProfileController {

    private final MerchantStoreProfileQueryApi queries;
    private final MerchantStoreProfileCommandApi commands;

    public MerchantStoreProfileController(MerchantStoreProfileQueryApi queries,
            MerchantStoreProfileCommandApi commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @ModelAttribute
    void cache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    Map<String, Object> get(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId, HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId");
        mini(request);
        MerchantStoreProfileDTO dto = queries.getStoreProfile(
                new StoreProfileQuery(id(merchantId), id(storeId), userQuery(request)));
        return envelope(wire(dto), request);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> update(@RequestBody String raw, HttpServletRequest request) {
        onlyParameters(request);
        mini(request);
        JsonNode body = StrictProfileBody.parse(raw);
        MerchantStoreProfileDTO dto = commands.updateStoreProfile(new UpdateStoreProfileCommand(
                id(text(body, "merchantId")), id(text(body, "storeId")),
                text(body, "storeName"), text(body, "address"), text(body, "phone"),
                text(body, "intro"), text(body, "announcement"),
                photoIds(body.get("photoAssetIds")), businessHours(body.get("businessHours")),
                promoLevel(body.get("promoLevel")), text(body, "promoTitle"), text(body, "promoText"),
                singleAsset(body.get("avatarAssetId")), text(body, "businessStatus"),
                text(body, "expectedVersion"),
                userCommand(request)));
        return envelope(wire(dto), request);
    }

    private static Map<String, Object> wire(MerchantStoreProfileDTO dto) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", dto.merchantId());
        out.put("storeId", dto.storeId());
        Map<String, Object> readonly = new LinkedHashMap<>();
        var facts = dto.readonlyFacts();
        readonly.put("storeName", facts.storeName());
        readonly.put("address", facts.address());
        readonly.put("cityCode", facts.cityCode());
        readonly.put("phoneMasked", facts.phoneMasked());
        readonly.put("longitude", facts.longitude());
        readonly.put("latitude", facts.latitude());
        readonly.put("merchantStatus", facts.merchantStatus());
        readonly.put("storeStatus", facts.storeStatus());
        readonly.put("operatingStatus", facts.operatingStatus());
        readonly.put("applicationStatus", facts.applicationStatus());
        out.put("readonly", readonly);
        Map<String, Object> profile = new LinkedHashMap<>();
        var value = dto.profile();
        profile.put("intro", value.intro());
        profile.put("announcement", value.announcement());
        profile.put("photoAssetIds", value.photoAssetIds() == null ? List.of() : value.photoAssetIds());
        profile.put("businessHours", value.businessHours() == null ? List.of() : value.businessHours());
        profile.put("promoLevel", value.promoLevel());
        profile.put("promoTitle", value.promoTitle());
        profile.put("promoText", value.promoText());
        profile.put("avatarAssetId", value.avatarAssetId());
        profile.put("businessStatus", value.businessStatus());
        profile.put("version", value.version());
        out.put("profile", profile);
        return out;
    }

    /** Single STORE_PHOTO avatar reference: empty string clears; otherwise a positive id. */
    private static String singleAsset(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.asText();
        if (value.isEmpty()) return value;
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        return value;
    }

    private static List<String> photoIds(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isTextual() || !item.asText().matches("[1-9][0-9]{0,18}")) throw invalid();
            values.add(item.asText());
        });
        return values;
    }

    private static List<MerchantStoreProfileDTO.BusinessHour> businessHours(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid();
        List<MerchantStoreProfileDTO.BusinessHour> hours = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isObject()) throw invalid();
            JsonNode days = item.get("days");
            if (days == null || !days.isArray() || days.isEmpty()) throw invalid();
            List<Integer> dayValues = new ArrayList<>();
            days.forEach(day -> {
                if (!day.isIntegralNumber()) throw invalid();
                dayValues.add(day.asInt());
            });
            JsonNode start = item.get("start");
            JsonNode end = item.get("end");
            if (start == null || !start.isTextual() || end == null || !end.isTextual()
                    || item.size() != 3) throw invalid();
            hours.add(new MerchantStoreProfileDTO.BusinessHour(List.copyOf(dayValues), start.asText(), end.asText()));
        });
        return hours;
    }

    private static Integer promoLevel(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) throw invalid();
        int value = node.asInt();
        if (value == 0) return null;
        if (value < 1 || value > 9) throw invalid();
        return value;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        return node.asText();
    }

    /** Strict full-replace body: every key present exactly once, explicit nulls rejected.
     *  2026-10-19 expansion adds the store facts (storeName/address/phone), the avatar asset
     *  and the business status switch. */
    private static final class StrictProfileBody {
        private static final Set<String> KEYS = Set.of("merchantId", "storeId", "storeName",
                "address", "phone", "intro", "announcement", "photoAssetIds", "businessHours",
                "promoLevel", "promoTitle", "promoText", "avatarAssetId", "businessStatus",
                "expectedVersion");

        private static JsonNode parse(String raw) {
            JsonNode body;
            try {
                body = com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader().readTree(raw);
            } catch (RuntimeException bad) {
                throw invalid();
            }
            if (body == null || !body.isObject() || body.size() != KEYS.size()
                    || !KEYS.containsAll(body.propertyNames())) throw invalid();
            for (String key : KEYS) {
                if (body.get(key) == null || body.get(key).isNull()) throw invalid();
            }
            return body;
        }
    }
}
