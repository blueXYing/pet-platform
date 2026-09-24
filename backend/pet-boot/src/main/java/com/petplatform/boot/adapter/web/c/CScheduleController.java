package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.AvailabilityPageDTO;
import com.petplatform.schedule.api.dto.AvailabilityWindowDTO;
import com.petplatform.schedule.api.query.AvailabilityQuery;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/services/{serviceId}/availability (CCR-W2-API-001 schedule domain, HTTP10 3.4,
 * approved 2026-09-23). SCH-D1: login is mandatory - the route is outside the STR-D8 anonymous
 * browse family, so the session filter enforces the MINIAPP bearer (anonymous 401). Minute-level
 * windows only, no fixed 60-minute slots; capacity stays server-computed and the assembly fails
 * closed (503) while the qualified-staff facts source is missing (SCH-D6).
 */
@RestController
@ConditionalOnProperty(prefix = "pet.schedule.query", name = "enabled", havingValue = "true")
public class CScheduleController {

    private static final DateTimeFormatter MINUTE_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ScheduleQueryApiImpl schedule;

    public CScheduleController(ScheduleQueryApiImpl schedule) {
        this.schedule = schedule;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/services/{serviceId}/availability")
    public ApiResponse<Map<String, Object>> availability(
            @PathVariable String serviceId,
            // required=false: a missing parameter must land in the 400 invalid-argument path,
            // not in Spring's binding exception (the scoped advice maps those to 500).
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        if (storeId == null || storeId.isBlank()) throw invalid("storeId");
        AvailabilityPageDTO value =
                schedule.queryAvailability(
                        new AvailabilityQuery(
                                serviceId,
                                storeId,
                                date(startDate, "startDate"),
                                date(endDate, "endDate"),
                                context(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CScheduleController::window).toList());
        return ApiResponse.success(data, trace(req));
    }

    /** Windows render in the platform business zone (+08:00) at minute precision. */
    private static Map<String, Object> window(AvailabilityWindowDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("start", format(item.start()));
        row.put("end", format(item.end()));
        row.put("effectiveCapacity", item.effectiveCapacity());
        row.put("occupiedCount", item.occupiedCount());
        row.put("remainingCapacity", item.remainingCapacity());
        row.put("available", item.available());
        return row;
    }

    private static String format(OffsetDateTime value) {
        return value.atZoneSameInstant(
                        com.petplatform.schedule.biz.application.AvailabilityQueryService.BUSINESS_ZONE)
                .toOffsetDateTime()
                .format(MINUTE_ISO);
    }

    /** SCH-D1: the filter guarantees the session on this protected path; absent means 401. */
    private static QueryContext context(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (view instanceof MiniSessionView session) {
            return new QueryContext(trace(req), OperatorType.USER, session.userId());
        }
        throw new ApiException(CommonApiCodes.UNAUTHORIZED, "login required");
    }

    private static LocalDate date(String raw, String field) {
        if (raw == null || !raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(field);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw invalid(field);
        }
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"storeId", "startDate", "endDate"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"storeId".equals(name) && !"startDate".equals(name) && !"endDate".equals(name)) {
                throw invalid(name);
            }
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
