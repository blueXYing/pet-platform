package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.error;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.id;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.noBody;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.ok;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.requestId;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.session;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.stringBody;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.time;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.trace;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.CommandContext;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableCommand;
import com.petplatform.order.api.command.AdminOrderDispositionApi;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseCommand;
import com.petplatform.refund.api.command.AdminRefundDispositionApi;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionCommand;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract-63 v0.2 disposal face (HTTP10 §5.2/§5.5/§5.6 drafts; user ruling 2026-10-15, doc 33):
 * four high-privilege write actions, same ADMIN_WEB bearer chain, ALL data scope and
 * {@code pet.admin.trade.http.enabled} face switch as the v0.1 read routes — default off. Every
 * route requires one terminal X-Request-Id (the idempotency binding lives in the owning kernel's
 * append-only audit row), relays the kernel-owned receipt verbatim (no adapter-side fact
 * building) and never renders sensitive material. The second confirmation is the operator UI's
 * same-person confirm (22号补充口径); the server-side gates are the action codes plus the kernel
 * re-checks. Paths are the HTTP10 drafted paths; contract 63 v0.2 is the implementing authority.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.trade.http.enabled"},
    havingValue = "true")
public class AdminTradeDispositionController {

    private static final Set<String> ABNORMAL_REASONS =
            Set.of("STORE_OFFLINE", "QUALIFICATION_INVALID", "MERCHANT_UNREACHABLE", "OTHER");

    private final AdminRefundDispositionApi refunds;
    private final AdminOrderDispositionApi orders;
    private final AdminMerchantDispositionApi merchants;

    public AdminTradeDispositionController(
            AdminRefundDispositionApi refunds,
            AdminOrderDispositionApi orders,
            AdminMerchantDispositionApi merchants) {
        this.refunds = refunds;
        this.orders = orders;
        this.merchants = merchants;
    }

    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping("/api/v1/admin/refunds/{refundId}/channel-query")
    Map<String, Object> channelQuery(@PathVariable String refundId, HttpServletRequest req) {
        noBody(req);
        AdminSessionView session = session(req, "refund.channel.query");
        var receipt = refunds.channelQuery(
                new DispositionCommand(id(refundId), command(req, session), refundAuthorization(session)));
        return ok(fields(receipt.refundOrderId(), receipt.action(), receipt.taskKey(),
                receipt.taskState(), receipt.refundStatus(), time(receipt.occurredAt())), req);
    }

    @PostMapping("/api/v1/admin/refunds/{refundId}/retry")
    Map<String, Object> retry(@PathVariable String refundId, HttpServletRequest req) {
        noBody(req);
        AdminSessionView session = session(req, "refund.retry");
        var receipt = refunds.retry(
                new DispositionCommand(id(refundId), command(req, session), refundAuthorization(session)));
        return ok(fields(receipt.refundOrderId(), receipt.action(), receipt.taskKey(),
                receipt.taskState(), receipt.refundStatus(), time(receipt.occurredAt())), req);
    }

    @PostMapping(value = "/api/v1/admin/orders/{orderId}/abnormal-close", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> abnormalClose(@PathVariable String orderId, HttpServletRequest req) {
        AdminSessionView session = session(req, "order.abnormal.close");
        var body = stringBody(req, "reasonCode", "reason");
        String reasonCode = body.get("reasonCode");
        String reason = body.get("reason");
        if (reasonCode == null || !ABNORMAL_REASONS.contains(reasonCode)
                || reason == null || reason.isBlank()) throw AdminTradeHttpSupport.invalid();
        var receipt = orders.abnormalClose(new AbnormalCloseCommand(id(orderId), reasonCode, reason,
                command(req, session), new AdminOrderDispositionApi.AdminAuthorization(
                        session.principal().sessionId(), session.principal().sessionGeneration())));
        return ok(fields(receipt.orderId(), receipt.orderStage(), receipt.cancelReason(),
                receipt.refundOrderId(), time(receipt.occurredAt())), req);
    }

    @PostMapping(value = "/api/v1/admin/merchants/{merchantId}/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> disable(@PathVariable String merchantId, HttpServletRequest req) {
        AdminSessionView session = session(req, "merchant.disable");
        var body = stringBody(req, "reason");
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) throw AdminTradeHttpSupport.invalid();
        var receipt = merchants.disable(new DisableCommand(id(merchantId), reason,
                command(req, session), new AdminMerchantDispositionApi.AdminAuthorization(
                        session.principal().sessionId(), session.principal().sessionGeneration())));
        return ok(fields(receipt.merchantId(), receipt.status(), time(receipt.occurredAt())), req);
    }

    private static CommandContext command(HttpServletRequest req, AdminSessionView session) {
        return new CommandContext(requestId(req), trace(req),
                com.petplatform.common.OperatorType.PLATFORM_OPERATOR,
                session.principal().operatorId(), "ADMIN_WEB");
    }

    private static AdminAuthorization refundAuthorization(AdminSessionView session) {
        return new AdminAuthorization(session.principal().sessionId(), session.principal().sessionGeneration());
    }

    private static Map<String, Object> fields(Object... values) {
        if (values.length % 2 != 0) throw new IllegalStateException("receipt projection must be paired");
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]);
        return out;
    }

    private static String time(OffsetDateTime value) {
        return AdminTradeHttpSupport.time(value);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> failure(RuntimeException failure, HttpServletRequest req) {
        Map<String, Object> body = error(failure, req);
        int status =
            switch (body.get("code").toString()) {
                case "COMMON_INVALID_ARGUMENT" -> 400;
                case "COMMON_UNAUTHORIZED" -> 401;
                case "COMMON_FORBIDDEN" -> 403;
                case "COMMON_NOT_FOUND" -> 404;
                case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT", "ORDER_STATE_NOT_ALLOWED",
                     "ORDER_REFUND_ALREADY_CREATED", "ORDER_OPERATION_BUSY",
                     "ORDER_CONFIRM_DEADLINE_PASSED" -> 409;
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
