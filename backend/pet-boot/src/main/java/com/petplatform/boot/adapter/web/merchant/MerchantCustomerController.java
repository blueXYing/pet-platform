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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Derived customer routes (contract 69, HTTP10 §4.8): read-only list + detail over the
 * store's own orders; masked phone only, breed-level pets only, no export/marketing surface.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "pet.order.customer.http.enabled", havingValue = "true")
public final class MerchantCustomerController {

    private final MerchantCustomerQueryApi queries;

    public MerchantCustomerController(MerchantCustomerQueryApi queries) {
        this.queries = queries;
    }

    @ModelAttribute
    void cache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "nickname", "page", "pageSize");
        mini(request);
        PageResult<CustomerRow> value = queries.listCustomers(
                new MerchantCustomerQueryApi.CustomerListQuery(id(merchantId), id(storeId),
                        blankToNull(nickname), number(page, 1), size(pageSize), userQuery(request)));
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
