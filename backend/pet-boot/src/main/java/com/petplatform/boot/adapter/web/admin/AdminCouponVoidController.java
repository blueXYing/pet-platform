package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.error;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.id;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.ok;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.requestId;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.session;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.stringBody;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.time;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.trace;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.coupon.api.command.AdminCouponVoidApi;
import com.petplatform.coupon.api.command.AdminCouponVoidApi.VoidCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponVoidApi.VoidReceipt;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract-65 §4.4 coupon void route (user ruling 2026-10-16 doc 36, minimal authorization):
 * the single operator voids one AVAILABLE coupon_instance (USED/FROZEN/RISK_FROZEN/EXPIRED
 * answer 409 in the kernel — a FROZEN coupon is locked by an in-flight order). Same ADMIN_WEB
 * bearer chain, ALL data scope and action-snapshot gate as the contract-65 ops face, with the
 * kernel re-proving {@code coupon.instance.void} per command. The route joins the
 * pet.admin.ops.http.enabled face, while the kernel behind it is gated by its own
 * {@code pet.coupon.void.enabled} switch (default off; flip unified under the 35号
 * switch-liberation ruling) — with the kernel unassembled the route answers 503 fail-closed
 * (61号 provider-unassembled semantics), never an open write. One terminal X-Request-Id
 * (23号); the idempotency binding lives in the owning coupon kernel.
 */
@RestController
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.ops.http.enabled"},
    havingValue = "true")
public class AdminCouponVoidController {

  private final ObjectProvider<AdminCouponVoidApi> voidKernel;

  public AdminCouponVoidController(ObjectProvider<AdminCouponVoidApi> voidKernel) {
    this.voidKernel = voidKernel;
  }

  @ModelAttribute
  void noStore(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @PostMapping(
      value = "/api/v1/admin/coupon-instances/{instanceId}/void",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> voidInstance(@PathVariable String instanceId, HttpServletRequest req) {
    AdminSessionView session = session(req, "coupon.instance.void");
    Map<String, String> body = stringBody(req, "reason");
    String reason = body.get("reason");
    if (reason == null || reason.isBlank()) throw AdminTradeHttpSupport.invalid();
    AdminCouponVoidApi kernel = voidKernel.getIfAvailable();
    if (kernel == null) {
      // pet.coupon.void.enabled=false: the face stays visible but the write fails closed.
      throw new ApiException(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE, "coupon void channel is not assembled");
    }
    VoidReceipt receipt = kernel.voidInstance(
        new VoidCouponCommand(id(instanceId), reason, command(req, session), authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("instanceId", receipt.instanceId());
    data.put("templateId", receipt.templateId());
    data.put("userId", receipt.userId());
    data.put("status", receipt.status());
    data.put("occurredAt", time(receipt.occurredAt()));
    return ok(data, req);
  }

  private static CommandContext command(HttpServletRequest req, AdminSessionView session) {
    return new CommandContext(requestId(req), trace(req), OperatorType.PLATFORM_OPERATOR,
        session.principal().operatorId(), "ADMIN_WEB");
  }

  private static AdminCouponVoidApi.AdminAuthorization authorization(AdminSessionView session) {
    return new AdminCouponVoidApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
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
          case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT" -> 409;
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
