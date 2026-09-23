package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetail;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewPage;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewSummary;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemResult;
import com.petplatform.service.api.dto.ServiceWriteTypes.DecideServiceReviewCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ForceOfflineServiceCommand;
import com.petplatform.service.biz.apiimpl.ServiceCommandApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceReviewQueryApiImpl;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin service review (HTTP10 §5 new, proposal SVCW-D7): review list/detail and the two
 * decisions (APPROVE/REJECT, force-offline). Real admin session with action codes
 * service.review.read / service.review.decide / service.forceOffline; decide and force-offline
 * revalidate transactionally inside the service module. Operators never manage merchant services
 * on the merchant's behalf (PRD 运营端 §3.3): there is no admin create/update/online/offline route.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/services", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.service.command", name = "enabled", havingValue = "true")
public final class ServiceWriteAdminController {

    private static final Set<String> STATUSES =
            Set.of("DRAFT", "REVIEWING", "ACTIVE", "OFFLINE", "REJECTED");

    private final ServiceCommandApiImpl commands;
    private final ServiceReviewQueryApiImpl queries;

    public ServiceWriteAdminController(
            ServiceCommandApiImpl commands, ServiceReviewQueryApiImpl queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String merchantId,
            HttpServletRequest req) {
        onlyParameters(req, "page", "pageSize", "status", "categoryId", "merchantId");
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 100
                || (status != null && !STATUSES.contains(status))) throw invalid();
        if (categoryId != null) id(categoryId);
        if (merchantId != null) id(merchantId);
        AdminSessionView session = admin(req, "service.review.read");
        ServiceReviewPage result =
                queries.listForReview(
                        new ServiceReviewListQuery(
                                page,
                                pageSize,
                                status,
                                categoryId,
                                merchantId,
                                serviceAuthorization(session),
                                adminQuery(req, session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(ServiceWriteAdminController::summary).toList());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return envelope(data, req);
    }

    @GetMapping("/{serviceId}")
    Map<String, Object> get(@PathVariable String serviceId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = admin(req, "service.review.read");
        return envelope(
                detail(
                        queries.getForReview(
                                new ServiceReviewDetailQuery(
                                        id(serviceId),
                                        serviceAuthorization(session),
                                        adminQuery(req, session)))),
                req);
    }

    @PostMapping(value = "/{serviceId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> decide(
            @PathVariable String serviceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        String decisionType = MerchantServiceRequests.text(json, "decisionType");
        if (!Set.of("APPROVE", "REJECT").contains(decisionType)) throw invalid();
        String opinion = MerchantServiceRequests.optionalText(json, "opinion", 500);
        AdminSessionView session = admin(req, "service.review.decide");
        ServiceItemResult result =
                commands.decideReview(
                        new DecideServiceReviewCommand(
                                id(serviceId),
                                version(MerchantServiceRequests.text(json, "expectedVersion")),
                                decisionType,
                                opinion,
                                serviceAuthorization(session),
                                adminCommand(req, session)));
        return envelope(receipt(result), req);
    }

    @PostMapping(value = "/{serviceId}/force-offline", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> forceOffline(
            @PathVariable String serviceId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        String reason = MerchantServiceRequests.text(json, "reason");
        if (reason.trim().length() < 10 || reason.length() > 500) throw invalid();
        // Lexicon-valid spelling of the approved force-offline action (see ServiceCommandService).
        AdminSessionView session = admin(req, "service.force.offline");
        ServiceItemResult result =
                commands.forceOffline(
                        new ForceOfflineServiceCommand(
                                id(serviceId),
                                version(MerchantServiceRequests.text(json, "expectedVersion")),
                                reason,
                                serviceAuthorization(session),
                                adminCommand(req, session)));
        return envelope(receipt(result), req);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> receipt(ServiceItemResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", r.serviceId());
        out.put("status", r.status());
        out.put("version", Long.toString(r.version()));
        if (r.decisionId() != null) out.put("decisionId", r.decisionId());
        if (r.actionId() != null) out.put("actionId", r.actionId());
        return out;
    }

    private static Map<String, Object> summary(ServiceReviewSummary s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", s.serviceId());
        out.put("merchantId", s.merchantId());
        out.put("storeId", s.storeId());
        out.put("serviceName", s.serviceName());
        out.put("categoryId", s.categoryId());
        out.put("categoryName", s.categoryName());
        out.put("status", s.status());
        out.put("price", s.price() == null ? null : s.price().toPlainString());
        out.put("listPrice", s.listPrice() == null ? null : s.listPrice().toPlainString());
        out.put("submissionNo", s.submissionNo());
        out.put("submittedAt", time(s.submittedAt()));
        out.put("slaRemainingMinutes", s.slaRemainingMinutes());
        out.put("rejectCount", s.rejectCount());
        out.put("version", Long.toString(s.version()));
        return out;
    }

    private static Map<String, Object> detail(ServiceReviewDetail d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", d.serviceId());
        out.put("merchantId", d.merchantId());
        out.put("storeId", d.storeId());
        out.put("serviceName", d.serviceName());
        out.put("categoryId", d.categoryId());
        out.put("categoryName", d.categoryName());
        out.put("status", d.status());
        out.put("price", d.price() == null ? null : d.price().toPlainString());
        out.put("listPrice", d.listPrice() == null ? null : d.listPrice().toPlainString());
        out.put("durationMinutes", d.durationMinutes());
        out.put("fulfillmentType", d.fulfillmentType());
        out.put("coverAssetId", d.coverAssetId());
        out.put("description", d.description());
        out.put("submissionNo", d.submissionNo());
        out.put("submittedAt", time(d.submittedAt()));
        out.put("slaRemainingMinutes", d.slaRemainingMinutes());
        out.put("version", Long.toString(d.version()));
        out.put("updatedAt", time(d.updatedAt()));
        out.put(
                "decisions",
                d.decisions().stream()
                        .map(x -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("decisionId", x.decisionId());
                            row.put("submissionNo", x.submissionNo());
                            row.put("decisionType", x.decisionType());
                            row.put("opinion", x.opinion());
                            row.put("decidedAt", time(x.decidedAt()));
                            return row;
                        })
                        .toList());
        return out;
    }

    private static com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization
        serviceAuthorization(AdminSessionView session) {
        return new com.petplatform.service.api.dto.ServiceWriteTypes.ServiceAdminAuthorization(
                session.principal().sessionId(), session.principal().sessionGeneration());
    }
}
