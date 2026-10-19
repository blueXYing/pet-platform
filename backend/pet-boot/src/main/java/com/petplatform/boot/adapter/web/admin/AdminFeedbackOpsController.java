package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.error;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.id;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.ok;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.onlyParameters;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.page;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.pageSize;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.requestId;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.session;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.customerservice.biz.application.FeedbackOpsAuthorizationPort.Authorization;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Contract-67 v0.2 feedback ops face (matrix row 18 complaint family; user ruling
 * 2026-10-19 "继续扩展CCR"): the ops list (status/type filters), the verbatim ops detail and
 * the two transitions PENDING→PROCESSING (complaint.handle) and PROCESSING→RESOLVED carrying
 * the single ≤1000-codepoint reply (complaint.decide). Same ADMIN_WEB bearer chain and ALL
 * data-scope gate as the 63/64/65 faces, riding the ops face switch plus this slice's own
 * switch (the kernel bean must be assembled); the kernel re-proves the session/action/scope
 * through the module-local port before touching rows (snapshot never evidence). No assign
 * route exists (dispatch/SLA stay CCR); WAITING_SUPPLEMENT/CLOSED stay unreachable words.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback-tickets")
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.admin.enabled", "admin.ops.http.enabled", "feedback.http.enabled"},
    havingValue = "true")
public class AdminFeedbackOpsController {

  private static final String[] LIST_PARAMS = {"page", "pageSize", "status", "type"};

  private final FeedbackTicketService tickets;

  public AdminFeedbackOpsController(FeedbackTicketService tickets) {
    this.tickets = tickets;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> list(@RequestParam java.util.Map<String, String> params,
      HttpServletRequest req) {
    onlyParameters(req, LIST_PARAMS);
    AdminSessionView view = session(req, "complaint.read");
    var result = tickets.listForOps(new FeedbackTicketService.OpsListQuery(
        authorization(view), query(req, view), params.get("status"), params.get("type"),
        params.get("page") == null ? null : page(params.get("page")),
        params.get("pageSize") == null ? null : pageSize(params.get("pageSize"))));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put("items", result.items().stream().map(AdminFeedbackOpsController::summaryFields).toList());
    return ok(data, req);
  }

  @GetMapping(value = "/{ticketId}", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> get(@PathVariable String ticketId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView view = session(req, "complaint.read");
    var detail = tickets.getForOps(new FeedbackTicketService.OpsTicketQuery(
        authorization(view), query(req, view), ticketId));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticketId", detail.ticketId());
    data.put("userId", detail.ownerUserId());
    data.put("type", detail.type());
    data.put("status", detail.status());
    data.put("content", detail.content());
    data.put("contact", detail.contact());
    data.put("contentPreview", detail.contentPreview());
    data.put("createdAt", detail.createdAt());
    data.put("attachmentAssetIds", detail.attachmentAssetIds());
    data.put("events", detail.events().stream().map(event -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("action", event.action());
      item.put("createdAt", event.createdAt());
      return item;
    }).toList());
    data.put("replies", detail.replies().stream().map(reply -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("reply", reply.reply());
      item.put("repliedAt", reply.repliedAt());
      return item;
    }).toList());
    return ok(data, req);
  }

  @PostMapping(value = "/{ticketId}/process", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> process(@PathVariable String ticketId, HttpServletRequest req) {
    requestId(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView view = session(req, "complaint.handle");
    var outcome = tickets.processTicket(new FeedbackTicketService.ProcessCommand(
        command(req, view), authorization(view), ticketId));
    return ok(transitionFields(outcome), req);
  }

  @PostMapping(value = "/{ticketId}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> resolve(@PathVariable String ticketId, @RequestBody String raw,
      HttpServletRequest req) {
    requestId(req);
    AdminSessionView view = session(req, "complaint.decide");
    String reply = text(strictObject(raw, "reply"), "reply");
    var outcome = tickets.resolveTicket(new FeedbackTicketService.ResolveCommand(
        command(req, view), authorization(view), ticketId, reply));
    return ok(transitionFields(outcome), req);
  }

  private static final tools.jackson.databind.ObjectReader STRICT =
      com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();

  private static JsonNode strictObject(String raw, String... fields) {
    try {
      JsonNode root = STRICT.readTree(raw);
      if (root == null || !root.isObject()) throw AdminTradeHttpSupport.invalid();
      var allowed = java.util.Set.of(fields);
      for (var property : root.propertyNames()) {
        if (!allowed.contains(property)) throw AdminTradeHttpSupport.invalid();
      }
      return root;
    } catch (RuntimeException failure) {
      if (failure == AdminTradeHttpSupport.invalid()) throw failure;
      throw AdminTradeHttpSupport.invalid();
    }
  }

  private static String text(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isTextual()) throw AdminTradeHttpSupport.invalid();
    return value.textValue();
  }

  private static Map<String, Object> transitionFields(FeedbackTicketService.ProcessOutcome outcome) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticketId", outcome.receipt().ticketId());
    data.put("status", outcome.receipt().status());
    data.put("version", Long.toString(outcome.receipt().version()));
    data.put("occurredAt", outcome.receipt().occurredAt().toString());
    return data;
  }

  private static Map<String, Object> summaryFields(FeedbackTicketService.TicketSummary summary) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("ticketId", summary.ticketId());
    item.put("type", summary.type());
    item.put("status", summary.status());
    item.put("contentPreview", summary.contentPreview());
    item.put("createdAt", summary.createdAt());
    return item;
  }

  private static Authorization authorization(AdminSessionView view) {
    return new Authorization(view.principal().sessionId(), view.principal().sessionGeneration());
  }

  private static com.petplatform.common.QueryContext query(HttpServletRequest req,
      AdminSessionView view) {
    return new com.petplatform.common.QueryContext(AdminTradeHttpSupport.trace(req),
        OperatorType.PLATFORM_OPERATOR, view.principal().operatorId());
  }

  private static CommandContext command(HttpServletRequest req, AdminSessionView view) {
    return new CommandContext(requestId(req), AdminTradeHttpSupport.trace(req),
        OperatorType.PLATFORM_OPERATOR, view.principal().operatorId(), "ADMIN_WEB");
  }

  @ExceptionHandler(ApiException.class)
  Map<String, Object> onApiException(ApiException failure, HttpServletRequest req) {
    return error(failure, req);
  }
}
