package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BlockedWindow;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.ReplaceCapabilitiesCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.StaffWindowItem;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.StaffWindowPage;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.WindowItem;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.WindowPage;
import com.petplatform.schedule.api.query.ScheduleMerchantQueryApi;
import com.petplatform.schedule.api.query.WorkbenchStaffWindowQuery;
import com.petplatform.schedule.api.query.WorkbenchWindowQuery;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantQueryApiImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Merchant schedule maintenance (SCH-004 write side; HTTP contract 52号). MINIAPP Bearer is
 * enforced by CBearerSessionFilter; every write carries the terminal X-Request-Id and replays
 * idempotently (23号): first window/staff-window create answers 201, replays and all other
 * commands answer 200. The owner gate, guards and audit live in the schedule module.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.schedule.command.http", name = "enabled",
        havingValue = "true")
public final class MerchantScheduleController {

    private static final ObjectReader STRICT =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> KINDS = Set.of("GENERAL", "PICKUP", "RETURN");
    private static final Set<String> WINDOW_STATUSES = Set.of("OPEN", "CLOSED");

    private final ScheduleMerchantCommandApiImpl commands;
    private final ScheduleMerchantQueryApiImpl workbench;

    public MerchantScheduleController(
            ScheduleMerchantCommandApiImpl commands, ScheduleMerchantQueryApiImpl workbench) {
        this.commands = commands;
        this.workbench = workbench;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    // ------------------------------------------------------------------ service windows

    @GetMapping("/stores/{storeId}/availability-windows")
    Map<String, Object> listWindows(
            @PathVariable String storeId,
            @RequestParam String merchantId,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "serviceId", "kind", "status");
        if ((kind != null && !KINDS.contains(kind))
                || (status != null && !WINDOW_STATUSES.contains(status))) throw invalid();
        var session = mini(req);
        WindowPage page = workbench.listWindows(new WorkbenchWindowQuery(
                id(merchantId), id(storeId), serviceId == null ? null : id(serviceId), kind,
                status, new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeId", page.storeId());
        data.put("items", page.items().stream().map(MerchantScheduleController::window).toList());
        return envelope(data, req);
    }

    @PostMapping(value = "/stores/{storeId}/availability-windows",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createWindow(
            @PathVariable String storeId, @RequestBody String json,
            HttpServletRequest req, HttpServletResponse response) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        CreateWindowCommand command = new CreateWindowCommand(
                userCommand(req),
                id(text(root, "merchantId")),
                id(storeId),
                id(text(root, "serviceId")),
                enumValue(root, "windowKind", KINDS),
                timestamp(text(root, "startAt")),
                timestamp(text(root, "endAt")),
                positiveInt(root, "configuredCapacity"));
        WindowResult[] out = new WindowResult[1];
        boolean created = commands.createWindowOutcome(command, out);
        response.setStatus(created ? 201 : 200);
        return envelope(window(out[0]), req);
    }

    @PutMapping(value = "/stores/{storeId}/availability-windows/{windowId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> updateWindow(
            @PathVariable String storeId, @PathVariable String windowId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        WindowResult result = commands.updateWindow(new UpdateWindowCommand(
                userCommand(req),
                id(windowId),
                id(text(root, "merchantId")),
                id(storeId),
                timestamp(text(root, "startAt")),
                timestamp(text(root, "endAt")),
                optionalInt(root, "configuredCapacity"),
                version(text(root, "expectedVersion")),
                optionalReason(root, "reason")));
        return envelope(window(result), req);
    }

    @PostMapping(value = "/stores/{storeId}/availability-windows/{windowId}/close",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> closeWindow(
            @PathVariable String storeId, @PathVariable String windowId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        WindowResult result = commands.closeWindow(new WindowCloseCommand(
                userCommand(req), id(windowId), id(text(root, "merchantId")), id(storeId),
                version(text(root, "expectedVersion")), text(root, "reason")));
        return envelope(window(result), req);
    }

    @PostMapping(value = "/stores/{storeId}/availability-windows/{windowId}/open",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> openWindow(
            @PathVariable String storeId, @PathVariable String windowId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        WindowResult result = commands.openWindow(new WindowOpenCommand(
                userCommand(req), id(windowId), id(text(root, "merchantId")), id(storeId),
                version(text(root, "expectedVersion"))));
        return envelope(window(result), req);
    }

    @PostMapping(value = "/stores/{storeId}/availability-windows/batch-close",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> batchClose(
            @PathVariable String storeId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        BatchCloseResult result = commands.batchClose(new BatchCloseCommand(
                userCommand(req), id(text(root, "merchantId")), id(storeId),
                date(text(root, "fromDate")), date(text(root, "toDate")),
                text(root, "reason")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeId", result.storeId());
        data.put("closedWindows", result.closedWindows().stream()
                .map(MerchantScheduleController::window).toList());
        data.put("blockedWindows", result.blockedWindows().stream()
                .map(MerchantScheduleController::blocked).toList());
        return envelope(data, req);
    }

    // ------------------------------------------------------------------ staff windows

    @GetMapping("/staff/{staffId}/availability-windows")
    Map<String, Object> listStaffWindows(
            @PathVariable String staffId,
            @RequestParam String merchantId,
            @RequestParam String storeId,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        mini(req);
        var session = mini(req);
        var page = workbench.listStaffWindows(new WorkbenchStaffWindowQuery(
                id(merchantId), id(storeId), id(staffId),
                new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeId", page.storeId());
        data.put("staffId", page.staffId());
        data.put("items", page.items().stream()
                .map(MerchantScheduleController::staffWindow).toList());
        return envelope(data, req);
    }

    @PostMapping(value = "/staff/{staffId}/availability-windows",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createStaffWindow(
            @PathVariable String staffId, @RequestBody String json,
            HttpServletRequest req, HttpServletResponse response) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        CreateStaffWindowCommand command = new CreateStaffWindowCommand(
                userCommand(req), id(text(root, "merchantId")), id(text(root, "storeId")),
                id(staffId), timestamp(text(root, "startAt")), timestamp(text(root, "endAt")));
        StaffWindowResult[] out = new StaffWindowResult[1];
        boolean created = commands.createStaffWindowOutcome(command, out);
        response.setStatus(created ? 201 : 200);
        return envelope(staffWindow(out[0]), req);
    }

    @PutMapping(value = "/staff/{staffId}/availability-windows/{windowId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> updateStaffWindow(
            @PathVariable String staffId, @PathVariable String windowId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        StaffWindowResult result = commands.updateStaffWindow(new UpdateStaffWindowCommand(
                userCommand(req), id(windowId), id(text(root, "merchantId")),
                id(text(root, "storeId")), id(staffId),
                timestamp(text(root, "startAt")), timestamp(text(root, "endAt")),
                version(text(root, "expectedVersion")), optionalReason(root, "reason")));
        return envelope(staffWindow(result), req);
    }

    @PostMapping(value = "/staff/{staffId}/availability-windows/{windowId}/close",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> closeStaffWindow(
            @PathVariable String staffId, @PathVariable String windowId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        StaffWindowResult result = commands.closeStaffWindow(new StaffWindowCloseCommand(
                userCommand(req), id(windowId), id(text(root, "merchantId")),
                id(text(root, "storeId")), id(staffId),
                version(text(root, "expectedVersion")), text(root, "reason")));
        return envelope(staffWindow(result), req);
    }

    @PostMapping(value = "/staff/{staffId}/availability-windows/{windowId}/open",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> openStaffWindow(
            @PathVariable String staffId, @PathVariable String windowId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        StaffWindowResult result = commands.openStaffWindow(new StaffWindowOpenCommand(
                userCommand(req), id(windowId), id(text(root, "merchantId")),
                id(text(root, "storeId")), id(staffId),
                version(text(root, "expectedVersion"))));
        return envelope(staffWindow(result), req);
    }

    // ------------------------------------------------------------------ capabilities

    @GetMapping("/staff/{staffId}/service-capabilities")
    Map<String, Object> getCapabilities(
            @PathVariable String staffId,
            @RequestParam String merchantId,
            @RequestParam String storeId,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "storeId");
        mini(req);
        var session = mini(req);
        // Read-only view: the terminal X-Request-Id is mandatory on writes only (23号 §2);
        // when absent the capability GET substitutes a local correlation id, nothing is bound.
        String requestId;
        try {
            requestId = requestId(req);
        } catch (ApiException missing) {
            requestId = UUID.randomUUID().toString();
        }
        CapabilityView view = commands.getCapabilities(new CapabilityQuery(
                new CommandContext(requestId, trace(req), OperatorType.USER, session.userId(),
                        "MINIAPP"),
                id(merchantId), id(storeId), id(staffId)));
        return envelope(capability(view.merchantId(), view.storeId(), view.staffId(),
                view.serviceIds(), view.version()), req);
    }

    @PutMapping(value = "/staff/{staffId}/service-capabilities",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> replaceCapabilities(
            @PathVariable String staffId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        mini(req);
        JsonNode root = readObject(json);
        JsonNode services = root.get("serviceIds");
        if (services == null || !services.isArray()) throw invalid();
        java.util.ArrayList<String> serviceIds = new java.util.ArrayList<>();
        for (int index = 0; index < services.size(); index++) {
            serviceIds.add(id(requireText(services.get(index), "serviceIds")));
        }
        CapabilityResult result = commands.replaceCapabilities(new ReplaceCapabilitiesCommand(
                userCommand(req), id(text(root, "merchantId")), id(text(root, "storeId")),
                id(staffId), List.copyOf(serviceIds),
                expectedVersion(text(root, "expectedVersion")),
                optionalReason(root, "reason")));
        return envelope(capability(result.merchantId(), result.storeId(), result.staffId(),
                result.serviceIds(), result.version()), req);
    }

    // ------------------------------------------------------------------ shaping

    private static Map<String, Object> window(WindowResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowId", r.windowId());
        out.put("merchantId", r.merchantId());
        out.put("storeId", r.storeId());
        out.put("serviceId", r.serviceId());
        out.put("windowKind", r.windowKind());
        out.put("startAt", time(r.startAt()));
        out.put("endAt", time(r.endAt()));
        out.put("configuredCapacity", r.configuredCapacity());
        out.put("status", r.status());
        out.put("version", r.version());
        return out;
    }

    private static Map<String, Object> window(WindowItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowId", i.windowId());
        out.put("merchantId", i.merchantId());
        out.put("storeId", i.storeId());
        out.put("serviceId", i.serviceId());
        out.put("windowKind", i.windowKind());
        out.put("startAt", time(i.startAt()));
        out.put("endAt", time(i.endAt()));
        out.put("configuredCapacity", i.configuredCapacity());
        out.put("status", i.status());
        out.put("version", i.version());
        out.put("updatedAt", time(i.updatedAt()));
        return out;
    }

    private static Map<String, Object> blocked(BlockedWindow b) {
        Map<String, Object> out = window(b.window());
        out.put("reasonCode", b.reasonCode());
        return out;
    }

    private static Map<String, Object> staffWindow(StaffWindowResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowId", r.windowId());
        out.put("merchantId", r.merchantId());
        out.put("storeId", r.storeId());
        out.put("staffId", r.staffId());
        out.put("startAt", time(r.startAt()));
        out.put("endAt", time(r.endAt()));
        out.put("status", r.status());
        out.put("version", r.version());
        return out;
    }

    private static Map<String, Object> staffWindow(StaffWindowItem i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowId", i.windowId());
        out.put("merchantId", i.merchantId());
        out.put("storeId", i.storeId());
        out.put("staffId", i.staffId());
        out.put("startAt", time(i.startAt()));
        out.put("endAt", time(i.endAt()));
        out.put("status", i.status());
        out.put("version", i.version());
        out.put("updatedAt", time(i.updatedAt()));
        return out;
    }

    private static Map<String, Object> capability(String merchantId, String storeId,
            String staffId, List<String> serviceIds, String version) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", merchantId);
        out.put("storeId", storeId);
        out.put("staffId", staffId);
        out.put("serviceIds", serviceIds);
        out.put("version", version);
        return out;
    }

    // ------------------------------------------------------------------ json helpers

    private static JsonNode readObject(String json) {
        try {
            JsonNode root = STRICT.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) throw invalid();
        return node.asString();
    }

    private static String requireText(JsonNode value, String field) {
        if (value == null || !value.isString() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }

    private static String enumValue(JsonNode root, String field, Set<String> values) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || !values.contains(node.asString())) throw invalid();
        return node.asString();
    }

    private static Integer optionalInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 1) {
            throw invalid();
        }
        return node.asInt();
    }

    private static int positiveInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.asInt() < 1) {
            throw invalid();
        }
        return node.asInt();
    }

    private static String optionalReason(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid();
        return node.asString();
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException malformed) {
            throw invalid();
        }
    }

    /** Same non-negative decimal lexicon as MerchantHttpSupport.version, kept as String. */
    private static String expectedVersion(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,18})")) throw invalid();
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
