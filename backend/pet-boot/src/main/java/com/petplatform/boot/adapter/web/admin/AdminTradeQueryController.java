package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.flag;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.id;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.instant;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.keyword;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.ok;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.onlyParameters;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.optionalId;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.page;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.pageSize;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.session;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.time;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiException;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.AdminOrderQueryApi;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminAuthorization;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetailQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderListQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminStatusLogQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundListQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationListQuery;
import com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminVerificationQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract-63 platform trade read face (HTTP10 §5.11, ADM-002/A-003): seven GET-only routes
 * over the order/refund/verification kernels. Every route resolves the real ADMIN_WEB session
 * (AdminBearerAuthenticationFilter), checks the snapshot action code and the ALL data scope,
 * and re-proves everything inside the owning kernel via the injected authorization port. This
 * adapter is a thin boundary: no business decision, no sensitive field (phone/address/contact
 * plaintext, credential or reason ciphers never render), Cache-Control: no-store everywhere.
 * Write dispositions (channel-query/retry, abnormal-close, merchant disable) stay drafted —
 * no route exists here for them.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/trade", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.trade.http.enabled"},
    havingValue = "true")
public class AdminTradeQueryController {

  private static final String[] ORDER_LIST_PARAMS = {
    "page", "pageSize", "displayStatus", "paymentStatus", "merchantId", "storeId",
    "orderNo", "keyword", "createdFrom", "createdTo", "rescheduledOnly"
  };
  private static final String[] REFUND_LIST_PARAMS = {
    "page", "pageSize", "status", "sourceType", "refundType", "orderId", "createdFrom", "createdTo"
  };
  private static final String[] VERIFICATION_LIST_PARAMS = {
    "page", "pageSize", "orderId", "storeId", "method", "verifiedFrom", "verifiedTo"
  };

  private final AdminOrderQueryApi orders;
  private final AdminRefundQueryApi refunds;
  private final AdminVerificationQueryApi verifications;

  public AdminTradeQueryController(
      AdminOrderQueryApi orders,
      AdminRefundQueryApi refunds,
      AdminVerificationQueryApi verifications) {
    this.orders = orders;
    this.refunds = refunds;
    this.verifications = verifications;
  }

  @ModelAttribute
  void noStore(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping("/orders")
  Map<String, Object> listOrders(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, ORDER_LIST_PARAMS);
    AdminSessionView session = session(req, "order.read");
    OffsetDateTime from = instant(params.get("createdFrom"));
    OffsetDateTime to = instant(params.get("createdTo"));
    var result =
        orders.listOrders(
            new AdminOrderListQuery(
                params.get("displayStatus"),
                params.get("paymentStatus"),
                optionalId(params.get("merchantId")),
                optionalId(params.get("storeId")),
                optionalId(params.get("orderNo")),
                keyword(params.get("keyword")),
                from,
                to,
                flag(params.get("rescheduledOnly")),
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                orderAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    // ARCH-005: the order module owns fact naming and the display derivation; this adapter
    // relays the order-owned wire projection verbatim (same discipline as the C-end slice).
    data.put(
        "items",
        result.items().stream()
            .map(com.petplatform.order.biz.application.AdminOrderSnapshotWire::fields)
            .toList());
    return ok(data, req);
  }

  @GetMapping("/orders/{orderId}")
  Map<String, Object> getOrder(@PathVariable String orderId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "order.read");
    var detail =
        orders.getOrder(
            new AdminOrderDetailQuery(id(orderId), query(req, session), orderAuthorization(session)));
    // Relay the order-owned wire projection verbatim; no fact/display mapping here.
    return ok(
        com.petplatform.order.biz.application.AdminOrderSnapshotWire.detailFields(detail), req);
  }

  @GetMapping("/orders/{orderId}/status-logs")
  Map<String, Object> listStatusLogs(@PathVariable String orderId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "order.status-log.read");
    var result =
        orders.listStatusLogs(
            new AdminStatusLogQuery(id(orderId), query(req, session), orderAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(com.petplatform.order.biz.application.AdminOrderSnapshotWire::logFields)
            .toList());
    return ok(data, req);
  }

  @GetMapping("/refunds")
  Map<String, Object> listRefunds(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, REFUND_LIST_PARAMS);
    AdminSessionView session = session(req, "refund.read");
    OffsetDateTime from = instant(params.get("createdFrom"));
    OffsetDateTime to = instant(params.get("createdTo"));
    var result =
        refunds.listRefunds(
            new AdminRefundListQuery(
                params.get("status"),
                params.get("sourceType"),
                params.get("refundType"),
                optionalId(params.get("orderId")),
                from,
                to,
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                refundAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put("items", result.items().stream().map(AdminTradeQueryController::refundSummary).toList());
    return ok(data, req);
  }

  @GetMapping("/refunds/{refundOrderId}")
  Map<String, Object> getRefund(@PathVariable String refundOrderId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "refund.read");
    var detail =
        refunds.getRefund(
            new AdminRefundQuery(id(refundOrderId), query(req, session), refundAuthorization(session)));
    Map<String, Object> data = refundSummary(new AdminRefundQueryApi.AdminRefundSummary(
        detail.refundOrderId(), detail.refundNo(), detail.orderId(), detail.refundType(),
        detail.sourceType(), detail.status(), detail.refundAmount(), detail.initiatorType(),
        detail.channelRefundNo(), detail.succeededAt(), detail.createdAt()));
    if (detail.application() == null) {
      data.put("application", null);
    } else {
      Map<String, Object> application = new LinkedHashMap<>();
      application.put("applicationId", detail.application().applicationId());
      application.put("status", detail.application().status());
      application.put("requestedAmount", money(detail.application().requestedAmount()));
      application.put("merchantDeadline", time(detail.application().merchantDeadline()));
      application.put("decidedAt", time(detail.application().decidedAt()));
      data.put("application", application);
    }
    if (detail.execution() == null) {
      data.put("execution", null);
    } else {
      Map<String, Object> execution = new LinkedHashMap<>();
      execution.put("channelTradeNo", detail.execution().channelTradeNo());
      execution.put("refundAmount", money(detail.execution().refundAmount()));
      execution.put("channelPaidAt", time(detail.execution().channelPaidAt()));
      execution.put("currency", detail.execution().currency());
      data.put("execution", execution);
    }
    return ok(data, req);
  }

  @GetMapping("/verifications")
  Map<String, Object> listVerifications(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, VERIFICATION_LIST_PARAMS);
    AdminSessionView session = session(req, "order.verification-log.read");
    OffsetDateTime from = instant(params.get("verifiedFrom"));
    OffsetDateTime to = instant(params.get("verifiedTo"));
    var result =
        verifications.listVerifications(
            new AdminVerificationListQuery(
                optionalId(params.get("orderId")),
                optionalId(params.get("storeId")),
                params.get("method"),
                from,
                to,
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                verificationAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream().map(AdminTradeQueryController::verificationSummary).toList());
    return ok(data, req);
  }

  @GetMapping("/verifications/{verificationId}")
  Map<String, Object> getVerification(@PathVariable String verificationId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "order.verification-log.read");
    var detail =
        verifications.getVerification(
            new AdminVerificationQuery(id(verificationId), query(req, session), verificationAuthorization(session)));
    Map<String, Object> data = verificationSummary(
        new AdminVerificationQueryApi.AdminVerificationSummary(
            detail.verificationId(), detail.verificationNo(), detail.orderId(), detail.storeId(),
            detail.verifyMethod(), detail.verifiedAt()));
    data.put("operatorStaffId", detail.operatorStaffId());
    data.put(
        "attempts",
        detail.attempts().stream()
            .map(
                attempt -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("attemptId", attempt.attemptId());
                  row.put("result", attempt.result());
                  row.put("failReason", attempt.failReason());
                  row.put("attemptedAt", time(attempt.attemptedAt()));
                  return row;
                })
            .toList());
    data.put(
        "riskAttempts",
        detail.riskAttempts().stream()
            .map(
                risk -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("riskAttemptId", risk.riskAttemptId());
                  row.put("resultCode", risk.resultCode());
                  row.put("countsFailure", risk.countsFailure());
                  row.put("attemptedAt", time(risk.attemptedAt()));
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  private static Map<String, Object> refundSummary(AdminRefundQueryApi.AdminRefundSummary refundRow) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("refundOrderId", refundRow.refundOrderId());
    row.put("refundNo", refundRow.refundNo());
    row.put("orderId", refundRow.orderId());
    row.put("refundType", refundRow.refundType());
    row.put("sourceType", refundRow.sourceType());
    row.put("status", refundRow.status());
    row.put("refundAmount", money(refundRow.refundAmount()));
    row.put("initiatorType", refundRow.initiatorType());
    row.put("channelRefundNo", refundRow.channelRefundNo());
    row.put("succeededAt", time(refundRow.succeededAt()));
    row.put("createdAt", time(refundRow.createdAt()));
    return row;
  }

  private static Map<String, Object> verificationSummary(
      AdminVerificationQueryApi.AdminVerificationSummary verificationRow) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("verificationId", verificationRow.verificationId());
    row.put("verificationNo", verificationRow.verificationNo());
    row.put("orderId", verificationRow.orderId());
    row.put("storeId", verificationRow.storeId());
    row.put("verifyMethod", verificationRow.verifyMethod());
    row.put("verifiedAt", time(verificationRow.verifiedAt()));
    return row;
  }

  private static String money(java.math.BigDecimal value) {
    return value.setScale(2).toPlainString();
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static QueryContext query(HttpServletRequest req, AdminSessionView session) {
    return new QueryContext(
        com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.trace(req),
        OperatorType.PLATFORM_OPERATOR,
        session.principal().operatorId());
  }

  private static AdminAuthorization orderAuthorization(AdminSessionView session) {
    return new AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization refundAuthorization(
      AdminSessionView session) {
    return new com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminAuthorization verificationAuthorization(
      AdminSessionView session) {
    return new com.petplatform.verification.api.query.AdminVerificationQueryApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, Object>> failure(RuntimeException failure, HttpServletRequest req) {
    Map<String, Object> body =
        com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.error(failure, req);
    int status =
        switch (body.get("code").toString()) {
          case "COMMON_INVALID_ARGUMENT" -> 400;
          case "COMMON_UNAUTHORIZED" -> 401;
          case "COMMON_FORBIDDEN" -> 403;
          case "COMMON_NOT_FOUND" -> 404;
          case "COMMON_DEPENDENCY_UNAVAILABLE" -> 503;
          default -> 500;
        };
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
        .header("Pragma", "no-cache")
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }
}
