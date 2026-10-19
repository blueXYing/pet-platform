package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.common.PageResult;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerDetail;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerRow;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.UserQueryApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Derived customer routes (contract 69, HTTP10 §4.8; 2026-10-19 expansion): read-only list +
 * detail over the store's own orders; masked phone only, breed-level pets only, no
 * export/marketing surface. The expansion adds the order-no-suffix search (hits only this
 * store's own order tails), the single internal note on the detail, and the OWNER note write.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "pet.order.customer.http.enabled", havingValue = "true")
public final class MerchantCustomerController {

    private final MerchantCustomerQueryApi queries;
    private final com.petplatform.order.api.query.MerchantCustomerNoteCommandApi notes;

    public MerchantCustomerController(MerchantCustomerQueryApi queries,
            com.petplatform.order.api.query.MerchantCustomerNoteCommandApi notes) {
        this.queries = queries;
        this.notes = notes;
    }

    @ModelAttribute
    void cache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String orderNoSuffix,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "nickname", "orderNoSuffix", "page", "pageSize");
        mini(request);
        PageResult<CustomerRow> value = queries.listCustomers(
                new MerchantCustomerQueryApi.CustomerListQuery(id(merchantId), id(storeId),
                        blankToNull(nickname), suffix(orderNoSuffix), number(page, 1),
                        size(pageSize), userQuery(request)));
        List<Map<String, Object>> items = value.items().stream().map(MerchantCustomerController::row).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return envelope(data, request);
    }

    @GetMapping("/{userId}")
    Map<String, Object> detail(@PathVariable String userId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId");
        mini(request);
        CustomerDetail detail = queries.getCustomer(new MerchantCustomerQueryApi.CustomerGetQuery(
                id(merchantId), id(storeId), id(userId), userQuery(request)));
        Map<String, Object> data = row(detail.customer());
        data.put("pets", detail.pets().stream().map(pet -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("petName", pet.petName());
            item.put("petType", pet.petType());
            item.put("breedName", pet.breedName());
            return item;
        }).toList());
        if (detail.note() == null) {
            data.put("note", null);
        } else {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("content", detail.note().content());
            note.put("version", detail.note().version());
            note.put("updatedAt", time(detail.note().updatedAt()));
            data.put("note", note);
        }
        return envelope(data, request);
    }

    /** 商家内部备注 (2026-10-19 expansion): one note per customer, OWNER write, idempotent. */
    @PutMapping(value = "/{userId}/note", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> updateNote(@PathVariable String userId, @RequestBody String raw,
            HttpServletRequest request) {
        onlyParameters(request);
        mini(request);
        JsonNode body = StrictNoteBody.parse(raw);
        var view = notes.updateNote(new com.petplatform.order.api.query.MerchantCustomerNoteCommandApi.UpdateNoteCommand(
                id(text(body, "merchantId")), id(text(body, "storeId")), id(userId),
                text(body, "note"), text(body, "expectedVersion"), userCommand(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", id(userId));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("content", view.content());
        note.put("version", view.version());
        note.put("updatedAt", time(view.updatedAt()));
        data.put("note", note);
        return envelope(data, request);
    }

    private static Map<String, Object> row(CustomerRow item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", item.userId());
        out.put("nickname", item.nickname());
        out.put("phoneMasked", item.phoneMasked());
        out.put("orderCount", item.orderCount());
        out.put("totalSpend", amount(item.totalSpend()));
        out.put("avgTicket", amount(item.avgTicket()));
        out.put("lastVisitAt", time(item.lastVisitAt()));
        if (item.topPet() == null) {
            out.put("topPet", null);
        } else {
            Map<String, Object> pet = new LinkedHashMap<>();
            pet.put("petName", item.topPet().petName());
            pet.put("petType", item.topPet().petType());
            pet.put("breedName", item.topPet().breedName());
            out.put("topPet", pet);
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 订单号尾号 (2026-10-19 expansion): 2..10 digits, sent as-is (leading zeros kept). */
    private static String suffix(String value) {
        return blankToNull(value);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        return node.asText();
    }

    /** Strict note body: every key present exactly once, explicit nulls rejected. */
    private static final class StrictNoteBody {
        private static final Set<String> KEYS = Set.of("merchantId", "storeId", "note", "expectedVersion");

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

    /** 11号 DecimalAmountOutput：恒两位小数（与商家订单列表同渲染）。 */
    private static String amount(java.math.BigDecimal value) {
        return value == null ? null
                : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static int number(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,10}")) throw invalid();
        long value = Long.parseLong(raw);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    private static int size(String raw) {
        if (raw == null || raw.isEmpty()) return 20;
        if (!raw.matches("[0-9]{1,3}")) throw invalid();
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 100) throw invalid();
        return value;
    }
}
