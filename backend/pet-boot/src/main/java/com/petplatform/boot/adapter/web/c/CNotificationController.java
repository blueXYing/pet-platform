package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.requestId;
import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.command.NotificationReadCommandApi.MarkReadCommand;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationItem;
import com.petplatform.notification.api.dto.NotificationTypes.NotificationPage;
import com.petplatform.notification.api.dto.NotificationTypes.ReadReceipt;
import com.petplatform.notification.biz.apiimpl.NotificationInboxApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/notifications, GET /{id}, POST /{id}/read (CCR-W2-NOTIFICATION-001). The
 * receiver is always the current session user; client parameters never select it.
 */
@RestController
@RequestMapping("/api/v1/c/notifications")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CNotificationController {

  private final NotificationInboxApiImpl inbox;

  public CNotificationController(NotificationInboxApiImpl inbox) {
    this.inbox = inbox;
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      HttpServletRequest req) {
    rejectUnknownParameters(req);
    MiniSessionView session = session(req);
    NotificationPage value =
        inbox.listInbox(
            parse(page, 1, 10_000, 1, "page"),
            parse(pageSize, 20, 50, 1, "pageSize"),
            new QueryContext(trace(req), OperatorType.USER, session.userId()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", value.items().stream().map(CNotificationController::body).toList());
    data.put("page", value.page());
    data.put("pageSize", value.pageSize());
    data.put("total", value.total());
    return ApiResponse.success(data, trace(req));
  }

  @GetMapping("/{notificationId}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable String notificationId, HttpServletRequest req) {
    if (req.getParameterMap().size() > 1 && !req.getParameterMap().isEmpty()) rejectQuery(req);
    MiniSessionView session = session(req);
    NotificationItem value =
        inbox.getInboxItem(
            notificationId, new QueryContext(trace(req), OperatorType.USER, session.userId()));
    return ApiResponse.success(body(value), trace(req));
  }

  @PostMapping("/{notificationId}/read")
  public ApiResponse<Map<String, Object>> read(
      @PathVariable String notificationId, HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) rejectQuery(req);
    MiniSessionView session = session(req);
    ReadReceipt value =
        inbox.markRead(
            new MarkReadCommand(
                notificationId,
                new CommandContext(
                    requestId(req), trace(req), OperatorType.USER, session.userId(), "C_MINIAPP")));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", value.id());
    data.put("readAt", format(value.readAt()));
    return ApiResponse.success(data, trace(req));
  }

  private static String format(OffsetDateTime value) {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(value.toInstant());
  }

  private static Map<String, Object> body(NotificationItem item) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", item.id());
    row.put("category", item.category());
    row.put("messageType", item.messageType());
    row.put("bizType", item.bizType());
    row.put("bizId", item.bizId());
    row.put("title", item.title());
    row.put("content", item.content());
    row.put("readAt", item.readAt() == null ? null : format(item.readAt()));
    row.put("createdAt", format(item.createdAt()));
    return row;
  }

  private static MiniSessionView session(HttpServletRequest req) {
    Object view = req.getAttribute(CBearerSessionFilter.VIEW);
    if (!(view instanceof MiniSessionView session)) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return session;
  }

  private static int parse(String raw, int fallback, int max, int min, String field) {
    if (raw == null || raw.isEmpty()) return fallback;
    if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
    int value = Integer.parseInt(raw);
    if (value < min || value > max) throw invalid(field);
    return value;
  }

  private static void rejectUnknownParameters(HttpServletRequest req) {
    for (String name : new String[] {"page", "pageSize"}) {
      String[] values = req.getParameterValues(name);
      if (values != null && values.length > 1) throw invalid(name);
    }
    for (String name : req.getParameterMap().keySet()) {
      if (!"page".equals(name) && !"pageSize".equals(name)) throw invalid(name);
    }
  }

  private static void rejectQuery(HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid("query");
  }

  private static ApiException invalid(String field) {
    return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
  }
}
