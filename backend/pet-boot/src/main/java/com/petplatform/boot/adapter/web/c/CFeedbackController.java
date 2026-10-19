package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/c/feedback-tickets face (contract 67 via HTTP10 §3.18): owner-scoped projections
 * pass through the kernel verbatim (ARCH-005 — the adapter adds no derived field); creation
 * is 201 on first execution and 200 on same-key idempotent replay (supplement 23 §5). The
 * detail view carries the frozen reply/resolution field slots (empty in V1 — the ops face is
 * a separate slice). A non-owner or missing ticket is one indistinguishable 404.
 */
@RestController
@RequestMapping("/api/v1/c/feedback-tickets")
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.c.enabled", "feedback.http.enabled"}, havingValue = "true")
public class CFeedbackController {

    private static final Set<String> CREATE_FIELDS = Set.of("type", "contact", "content");

    private final FeedbackTicketService tickets;

    public CFeedbackController(FeedbackTicketService tickets) {
        this.tickets = tickets;
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object attribute = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(attribute instanceof MiniSessionView view)) {
            throw new IllegalArgumentException("Session view missing");
        }
        return view;
    }

    private static CommandContext context(HttpServletRequest req, MiniSessionView view) {
        return new CommandContext(
                CShared.requestId(req), CShared.trace(req), OperatorType.USER,
                view.userId(), "C_MINIAPP");
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestBody Request body, HttpServletRequest req, HttpServletResponse response) {
        body.require(CREATE_FIELDS, "type", "content");
        MiniSessionView view = session(req);
        FeedbackTicketService.CreateOutcome outcome = tickets.create(
                new FeedbackTicketService.CreateFeedbackCommand(
                        context(req, view), body.string("type"), body.string("contact"),
                        body.string("content")));
        response.setStatus(outcome.replayed() ? 200 : 201);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", outcome.receipt().ticketId());
        data.put("type", outcome.receipt().type());
        data.put("status", outcome.receipt().status());
        data.put("createdAt", outcome.receipt().createdAt());
        return ApiResponse.success(data, CShared.trace(req));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest req) {
        MiniSessionView view = session(req);
        FeedbackTicketService.TicketPage result = tickets.listMine(view.userId(), page, pageSize);
        List<Map<String, Object>> items = result.items().stream()
                .map(CFeedbackController::toBody).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        return ApiResponse.success(data, CShared.trace(req));
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<Map<String, Object>> get(
            @PathVariable String ticketId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        FeedbackTicketService.TicketDetail detail = tickets.getMine(view.userId(), ticketId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", detail.ticketId());
        data.put("type", detail.type());
        data.put("status", detail.status());
        data.put("content", detail.content());
        data.put("contact", detail.contact());
        data.put("contentPreview", detail.contentPreview());
        data.put("createdAt", detail.createdAt());
        data.put("events", detail.events().stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", event.action());
            item.put("createdAt", event.createdAt());
            return item;
        }).toList());
        data.put("replies", detail.replies());
        data.put("resolution", detail.resolution());
        return ApiResponse.success(data, CShared.trace(req));
    }

    private static Map<String, Object> toBody(FeedbackTicketService.TicketSummary summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", summary.ticketId());
        body.put("type", summary.type());
        body.put("status", summary.status());
        body.put("contentPreview", summary.contentPreview());
        body.put("createdAt", summary.createdAt());
        return body;
    }
}
