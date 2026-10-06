package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.*;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffMemberApiImpl;
import com.petplatform.merchant.api.query.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/** Contract 54 OWNER routes: member management behind the default-off binding switch. */
@RestController
@RequestMapping(value = "/api/v1/merchant/staff-members", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.staff-member", name = "enabled", havingValue = "true")
public final class MerchantStaffMemberController {
    private static final ObjectReader STRICT = com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private final MerchantStaffMemberApiImpl members;
    public MerchantStaffMemberController(MerchantStaffMemberApiImpl members) { this.members = members; }

    @ModelAttribute void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    Map<String, Object> listMembers(@RequestParam String merchantId, @RequestParam String storeId,
            @RequestParam(required = false) String page, @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "page", "pageSize");
        MerchantStaffMemberPageDTO result = members.listMembers(new StaffMemberManagementQuery(
                id(merchantId), id(storeId), page(page, 1, 10_000), page(pageSize, 20, 100),
                userQuery(req)));
        return envelopePage(result.items(), result.page(), result.pageSize(), result.total(), req);
    }

    @GetMapping("/invitations")
    Map<String, Object> listInvitations(@RequestParam String merchantId, @RequestParam String storeId,
            @RequestParam(required = false) String page, @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId", "page", "pageSize");
        MerchantStaffInvitationPageDTO result = members.listInvitations(new StaffInvitationManagementQuery(
                id(merchantId), id(storeId), page(page, 1, 10_000), page(pageSize, 20, 100),
                userQuery(req)));
        return envelopePage(result.items(), result.page(), result.pageSize(), result.total(), req);
    }

    @PostMapping(value = "/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> invite(@RequestBody String json, HttpServletRequest req,
            HttpServletResponse response) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId", "storeId", "phone", "memberName", "actions"));
        MerchantStaffInvitationCommandResult result = members.inviteMember(new InviteStaffMemberCommand(
                id(requiredText(body, "merchantId")), id(requiredText(body, "storeId")),
                requiredText(body, "phone"), requiredText(body, "memberName"),
                strings(body, "actions"), userCommand(req)));
        response.setStatus(result.created() ? 201 : 200);
        return envelope(result.invitation(), req);
    }

    @PostMapping(value = "/invitations/{invitationId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> cancel(@PathVariable String invitationId, @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId", "storeId", "expectedVersion"));
        MerchantStaffInvitationCommandResult result = members.cancelInvitation(
                new CancelStaffMemberInvitationCommand(id(requiredText(body, "merchantId")),
                        id(requiredText(body, "storeId")), id(invitationId),
                        Long.toString(version(requiredText(body, "expectedVersion"))), userCommand(req)));
        return envelope(result.invitation(), req);
    }

    @PostMapping(value = "/{memberId}/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> disable(@PathVariable String memberId, @RequestBody String json,
            HttpServletRequest req) {
        return lifecycle(memberId, json, req, "disable");
    }

    @PostMapping(value = "/{memberId}/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> enable(@PathVariable String memberId, @RequestBody String json,
            HttpServletRequest req) {
        return lifecycle(memberId, json, req, "enable");
    }

    @PostMapping(value = "/{memberId}/revoke-store", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> revokeStore(@PathVariable String memberId, @RequestBody String json,
            HttpServletRequest req) {
        return lifecycle(memberId, json, req, "revoke-store");
    }

    @PostMapping(value = "/{memberId}/grant-actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> grantActions(@PathVariable String memberId, @RequestBody String json,
            HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId", "storeId", "actions", "expectedVersion"));
        MerchantStaffMemberCommandResult result = members.grantActions(new GrantStaffMemberActionsCommand(
                id(requiredText(body, "merchantId")), id(requiredText(body, "storeId")), id(memberId),
                strings(body, "actions"), Long.toString(version(requiredText(body, "expectedVersion"))),
                userCommand(req)));
        return envelope(result.member(), req);
    }

    private Map<String, Object> lifecycle(String memberId, String json, HttpServletRequest req,
            String action) {
        onlyParameters(req);
        mini(req);
        JsonNode body = body(json, Set.of("merchantId", "storeId", "expectedVersion"));
        StaffMemberLifecycleCommand command = new StaffMemberLifecycleCommand(
                id(requiredText(body, "merchantId")), id(requiredText(body, "storeId")), id(memberId),
                Long.toString(version(requiredText(body, "expectedVersion"))), userCommand(req));
        MerchantStaffMemberCommandResult result = switch (action) {
            case "disable" -> members.disableMember(command);
            case "enable" -> members.enableMember(command);
            case "revoke-store" -> members.revokeStoreGrant(command);
            default -> throw invalid();
        };
        return envelope(result.member(), req);
    }

    private static Map<String, Object> envelopePage(List<?> items, int page, int pageSize, long total,
            HttpServletRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("total", total);
        return envelope(data, req);
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
    private static List<String> strings(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) throw invalid();
        List<String> values = new ArrayList<>();
        value.forEach(entry -> {
            if (entry == null || !entry.isTextual()) throw invalid();
            values.add(entry.asText());
        });
        return values;
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
