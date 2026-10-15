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
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminAuthorization;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantDetailQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffInvitationListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffMemberListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminReservationListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminStaffWindowListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminWindowListQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract-64 platform governance read face (HTTP10 §5.12, ADM-001 gap slice / A-002 phase 2):
 * eight GET-only routes over the merchant and schedule kernels. Every route resolves the real
 * ADMIN_WEB session (AdminBearerAuthenticationFilter), checks the snapshot action code and the
 * ALL data scope, and re-proves everything inside the owning kernel via the injected
 * authorization port. This adapter is a thin boundary: no business decision, no masked field
 * unmasked (phones arrive pre-masked from the kernels; provider numbers keep four digits),
 * Cache-Control: no-store everywhere. Write dispositions (merchant §5.6 disable/freeze, every
 * staff action) stay drafted — no write route exists here.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/governance", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.governance.http.enabled"},
    havingValue = "true")
public class AdminGovernanceQueryController {

  private static final String[] LEDGER_LIST_PARAMS = {
    "page", "pageSize", "status", "storeStatus", "cityCode", "merchantTypeCode",
    "keyword", "createdFrom", "createdTo"
  };
  private static final String[] WINDOW_LIST_PARAMS = {
    "page", "pageSize", "merchantId", "storeId", "serviceId", "windowKind", "status",
    "startFrom", "startTo"
  };
  private static final String[] STAFF_WINDOW_LIST_PARAMS = {
    "page", "pageSize", "storeId", "staffId", "status", "startFrom", "startTo"
  };
  private static final String[] RESERVATION_LIST_PARAMS = {
    "page", "pageSize", "merchantId", "storeId", "serviceId", "orderId", "status",
    "startFrom", "startTo"
  };
  private static final String[] STAFF_LIST_PARAMS = {
    "page", "pageSize", "storeId", "employmentStatus", "serviceEnabled"
  };
  private static final String[] MEMBER_LIST_PARAMS = {"page", "pageSize", "storeId", "status"};
  private static final String[] INVITATION_LIST_PARAMS = {"page", "pageSize", "storeId", "status"};

  private final AdminMerchantGovernanceQueryApi merchants;
  private final AdminScheduleQueryApi schedule;

  public AdminGovernanceQueryController(
      AdminMerchantGovernanceQueryApi merchants, AdminScheduleQueryApi schedule) {
    this.merchants = merchants;
    this.schedule = schedule;
  }

  @ModelAttribute
  void noStore(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping("/merchants")
  Map<String, Object> listMerchants(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, LEDGER_LIST_PARAMS);
    AdminSessionView session = session(req, "merchant.read");
    OffsetDateTime from = instant(params.get("createdFrom"));
    OffsetDateTime to = instant(params.get("createdTo"));
    var result =
        merchants.listMerchants(
            new AdminMerchantListQuery(
                params.get("status"),
                params.get("storeStatus"),
                params.get("cityCode"),
                params.get("merchantTypeCode"),
                keyword(params.get("keyword")),
                from,
                to,
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put("items", result.items().stream().map(AdminGovernanceQueryController::ledgerRow).toList());
    return ok(data, req);
  }

  @GetMapping("/merchants/{merchantId}")
  Map<String, Object> getMerchant(@PathVariable String merchantId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "merchant.read");
    var detail =
        merchants.getMerchant(
            new AdminMerchantDetailQuery(id(merchantId), query(req, session), authorization(session)));
    Map<String, Object> data = ledgerRow(
        new AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary(
            detail.merchantId(), detail.merchantName(), detail.status(), detail.merchantTypeCode(),
            detail.cityCode(), detail.storeCount(), detail.activeStoreCount(),
            detail.applicationStatus(), detail.signingStatus(), detail.providerMerchantNoMasked(),
            detail.createdAt()));
    data.put(
        "stores",
        detail.stores().stream()
            .map(
                store -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("storeId", store.storeId());
                  row.put("storeName", store.storeName());
                  row.put("address", store.address());
                  row.put("status", store.status());
                  return row;
                })
            .toList());
    if (detail.application() == null) {
      data.put("application", null);
    } else {
      Map<String, Object> application = new LinkedHashMap<>();
      application.put("applicationId", detail.application().applicationId());
      application.put("applicationNo", detail.application().applicationNo());
      application.put("status", detail.application().status());
      application.put("submittedAt", time(detail.application().submittedAt()));
      application.put("reviewedAt", time(detail.application().reviewedAt()));
      data.put("application", application);
    }
    if (detail.signing() == null) {
      data.put("signing", null);
    } else {
      Map<String, Object> signing = new LinkedHashMap<>();
      signing.put("signingStatus", detail.signing().signingStatus());
      signing.put("agreementVersion", detail.signing().agreementVersion());
      signing.put("acceptedAt", time(detail.signing().acceptedAt()));
      data.put("signing", signing);
    }
    return ok(data, req);
  }

  @GetMapping("/schedule/windows")
  Map<String, Object> listWindows(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, WINDOW_LIST_PARAMS);
    AdminSessionView session = session(req, "schedule.read");
    var range = range(params);
    var result =
        schedule.listWindows(
            new AdminWindowListQuery(
                optionalId(params.get("merchantId")),
                optionalId(params.get("storeId")),
                optionalId(params.get("serviceId")),
                params.get("windowKind"),
                params.get("status"),
                range[0],
                range[1],
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                scheduleAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                window -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("windowId", window.windowId());
                  row.put("merchantId", window.merchantId());
                  row.put("storeId", window.storeId());
                  row.put("serviceId", window.serviceId());
                  row.put("windowKind", window.windowKind());
                  row.put("startAt", time(window.startAt()));
                  row.put("endAt", time(window.endAt()));
                  row.put("configuredCapacity", window.configuredCapacity());
                  row.put("status", window.status());
                  row.put("version", window.version());
                  row.put("updatedAt", time(window.updatedAt()));
                  row.put("confirmedCount", window.confirmedCount());
                  row.put("tempLockedCount", window.tempLockedCount());
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  @GetMapping("/schedule/staff-windows")
  Map<String, Object> listStaffWindows(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, STAFF_WINDOW_LIST_PARAMS);
    AdminSessionView session = session(req, "schedule.read");
    var range = range(params);
    var result =
        schedule.listStaffWindows(
            new AdminStaffWindowListQuery(
                optionalId(params.get("storeId")),
                optionalId(params.get("staffId")),
                params.get("status"),
                range[0],
                range[1],
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                scheduleAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                window -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("windowId", window.windowId());
                  row.put("storeId", window.storeId());
                  row.put("staffId", window.staffId());
                  row.put("startAt", time(window.startAt()));
                  row.put("endAt", time(window.endAt()));
                  row.put("status", window.status());
                  row.put("version", window.version());
                  row.put("updatedAt", time(window.updatedAt()));
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  @GetMapping("/schedule/reservations")
  Map<String, Object> listReservations(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, RESERVATION_LIST_PARAMS);
    AdminSessionView session = session(req, "schedule.read");
    var range = range(params);
    var result =
        schedule.listReservations(
            new AdminReservationListQuery(
                optionalId(params.get("merchantId")),
                optionalId(params.get("storeId")),
                optionalId(params.get("serviceId")),
                optionalId(params.get("orderId")),
                params.get("status"),
                range[0],
                range[1],
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                scheduleAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                reservation -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("reservationId", reservation.reservationId());
                  row.put("orderId", reservation.orderId());
                  row.put("storeId", reservation.storeId());
                  row.put("serviceId", reservation.serviceId());
                  row.put("fulfillmentType", reservation.fulfillmentType());
                  row.put("startAt", time(reservation.startAt()));
                  row.put("endAt", time(reservation.endAt()));
                  row.put("pickupStartAt", time(reservation.pickupStartAt()));
                  row.put("returnStartAt", time(reservation.returnStartAt()));
                  row.put("status", reservation.status());
                  row.put("lockExpireAt", time(reservation.lockExpireAt()));
                  row.put("capacitySnapshot", reservation.capacitySnapshot());
                  row.put("qualifiedStaffCountSnapshot", reservation.qualifiedStaffCountSnapshot());
                  row.put("createdAt", time(reservation.createdAt()));
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  @GetMapping("/merchants/{merchantId}/staff")
  Map<String, Object> listStaff(
      @PathVariable String merchantId,
      @RequestParam java.util.Map<String, String> params,
      HttpServletRequest req) {
    onlyParameters(req, STAFF_LIST_PARAMS);
    AdminSessionView session = session(req, "merchant.staff.read");
    var result =
        merchants.listStaff(
            new AdminStaffListQuery(
                id(merchantId),
                optionalId(params.get("storeId")),
                params.get("employmentStatus"),
                optionalFlag(params.get("serviceEnabled")),
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                staff -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("staffId", staff.staffId());
                  row.put("storeId", staff.storeId());
                  row.put("staffName", staff.staffName());
                  row.put("phoneMasked", staff.phoneMasked());
                  row.put("employmentStatus", staff.employmentStatus());
                  row.put("serviceEnabled", staff.serviceEnabled());
                  row.put("version", staff.version());
                  row.put("createdAt", time(staff.createdAt()));
                  row.put("updatedAt", time(staff.updatedAt()));
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  @GetMapping("/merchants/{merchantId}/staff-members")
  Map<String, Object> listStaffMembers(
      @PathVariable String merchantId,
      @RequestParam java.util.Map<String, String> params,
      HttpServletRequest req) {
    onlyParameters(req, MEMBER_LIST_PARAMS);
    AdminSessionView session = session(req, "merchant.staff.read");
    var result =
        merchants.listStaffMembers(
            new AdminStaffMemberListQuery(
                id(merchantId),
                optionalId(params.get("storeId")),
                params.get("status"),
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                member -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("memberId", member.memberId());
                  row.put("storeId", member.storeId());
                  row.put("status", member.status());
                  row.put("memberName", member.memberName());
                  row.put("phoneMasked", member.phoneMasked());
                  row.put("grantedActions", member.grantedActions());
                  row.put("grantRevoked", member.grantRevoked());
                  row.put("memberVersion", member.memberVersion());
                  row.put("grantVersion", member.grantVersion());
                  row.put("createdAt", time(member.createdAt()));
                  row.put("updatedAt", time(member.updatedAt()));
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  @GetMapping("/merchants/{merchantId}/staff-members/invitations")
  Map<String, Object> listStaffInvitations(
      @PathVariable String merchantId,
      @RequestParam java.util.Map<String, String> params,
      HttpServletRequest req) {
    onlyParameters(req, INVITATION_LIST_PARAMS);
    AdminSessionView session = session(req, "merchant.staff.read");
    var result =
        merchants.listStaffInvitations(
            new AdminStaffInvitationListQuery(
                id(merchantId),
                optionalId(params.get("storeId")),
                params.get("status"),
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                invitation -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("invitationId", invitation.invitationId());
                  row.put("storeId", invitation.storeId());
                  row.put("memberName", invitation.memberName());
                  row.put("phoneMasked", invitation.phoneMasked());
                  row.put("status", invitation.status());
                  row.put("grantedActions", invitation.grantedActions());
                  row.put("invitedBy", invitation.invitedBy());
                  row.put("invitedAt", time(invitation.invitedAt()));
                  row.put("updatedAt", time(invitation.updatedAt()));
                  return row;
                })
            .toList());
    return ok(data, req);
  }

  private static Map<String, Object> ledgerRow(
      AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("merchantId", row.merchantId());
    data.put("merchantName", row.merchantName());
    data.put("status", row.status());
    data.put("merchantTypeCode", row.merchantTypeCode());
    data.put("cityCode", row.cityCode());
    data.put("storeCount", row.storeCount());
    data.put("activeStoreCount", row.activeStoreCount());
    data.put("applicationStatus", row.applicationStatus());
    data.put("signingStatus", row.signingStatus());
    data.put("providerMerchantNoMasked", row.providerMerchantNoMasked());
    data.put("createdAt", time(row.createdAt()));
    return data;
  }

  private static Boolean optionalFlag(String value) {
    if (value == null) return null;
    return flag(value);
  }

  private static OffsetDateTime[] range(java.util.Map<String, String> params) {
    return new OffsetDateTime[] {instant(params.get("startFrom")), instant(params.get("startTo"))};
  }

  private static QueryContext query(HttpServletRequest req, AdminSessionView session) {
    return new QueryContext(
        com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.trace(req),
        OperatorType.PLATFORM_OPERATOR,
        session.principal().operatorId());
  }

  private static AdminAuthorization authorization(AdminSessionView session) {
    return new AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static AdminScheduleQueryApi.AdminAuthorization scheduleAuthorization(
      AdminSessionView session) {
    return new AdminScheduleQueryApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  @ExceptionHandler(RuntimeException.class)
  org.springframework.http.ResponseEntity<Map<String, Object>> failure(
      RuntimeException failure, HttpServletRequest req) {
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
    return org.springframework.http.ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
        .header("Pragma", "no-cache")
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }
}
