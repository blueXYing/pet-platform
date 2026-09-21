package com.petplatform.boot.adapter.web.merchant;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.*;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.MDC;

final class MerchantHttpSupport {
  private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern TIME =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})");

  private MerchantHttpSupport() {}

  static MiniSessionView mini(HttpServletRequest req) {
    Object view = req.getAttribute(CBearerSessionFilter.VIEW);
    if (view instanceof MiniSessionView s) return s;
    throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
  }

  static AdminSessionView admin(HttpServletRequest req, String... actions) {
    Object view = req.getAttribute(AdminBearerAuthenticationFilter.VIEW);
    if (!(view instanceof AdminSessionView s))
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    for (String action : actions) {
      if (!s.permissions().actionCodes().contains(action))
        throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }
    return s;
  }

  static QueryContext userQuery(HttpServletRequest req) {
    return new QueryContext(trace(req), OperatorType.USER, mini(req).userId());
  }

  static CommandContext userCommand(HttpServletRequest req) {
    return new CommandContext(
        requestId(req), trace(req), OperatorType.USER, mini(req).userId(), "MINIAPP");
  }

  static QueryContext adminQuery(HttpServletRequest req, AdminSessionView s) {
    return new QueryContext(trace(req), OperatorType.PLATFORM_OPERATOR, s.principal().operatorId());
  }

  static CommandContext adminCommand(HttpServletRequest req, AdminSessionView s) {
    return new CommandContext(
        requestId(req),
        trace(req),
        OperatorType.PLATFORM_OPERATOR,
        s.principal().operatorId(),
        "ADMIN_WEB");
  }

  static AdminAuthorizationReference authorization(AdminSessionView s) {
    return new AdminAuthorizationReference(
        s.principal().sessionId(), s.principal().sessionGeneration());
  }

  static String trace(HttpServletRequest req) {
    String trace = MDC.get(TraceContextFilter.TRACE_MDC_KEY);
    if (trace != null && !trace.isBlank()) return trace;
    Object value = req.getAttribute("merchantTraceId");
    if (value instanceof String s) return s;
    trace = UUID.randomUUID().toString();
    req.setAttribute("merchantTraceId", trace);
    return trace;
  }

  static String requestId(HttpServletRequest req) {
    try {
      return PublicContractChecks.requireTerminalRequestId(req.getHeader("X-Request-Id"));
    } catch (RuntimeException bad) {
      throw invalid();
    }
  }

  static void noDuplicateParameters(HttpServletRequest req, String... names) {
    for (String name : names) {
      String[] values = req.getParameterValues(name);
      if (values != null && values.length != 1) throw invalid();
    }
  }

  static void onlyParameters(HttpServletRequest req, String... names) {
    Set<String> allowed = Set.of(names);
    if (!allowed.containsAll(req.getParameterMap().keySet())) throw invalid();
    noDuplicateParameters(req, names);
  }

  static String id(String value) {
    try {
      if (value == null || !ID.matcher(value).matches() || Long.parseLong(value) <= 0)
        throw invalid();
      return value;
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  static long version(String value) {
    try {
      if (value == null || !value.matches("(0|[1-9][0-9]{0,18})")) throw invalid();
      long parsed = Long.parseLong(value);
      if (parsed < 0) throw invalid();
      return parsed;
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  static String hash(String value) {
    if (value == null || !HASH.matcher(value).matches()) throw invalid();
    return value;
  }

  static OffsetDateTime timestamp(String value) {
    try {
      if (value == null || !TIME.matcher(value).matches()) throw invalid();
      return PublicContractChecks.requireMillisecondPrecision(OffsetDateTime.parse(value));
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  static String time(OffsetDateTime value) {
    return value == null
        ? null
        : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
  }

  static String decimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  static ApiException invalid() {
    return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
  }

  static Map<String, Object> envelope(Object data, HttpServletRequest req) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("success", true);
    out.put("code", "SUCCESS");
    out.put("message", "成功");
    out.put("data", data);
    out.put("traceId", trace(req));
    return out;
  }

  static Map<String, Object> receipt(MerchantApplicationResult r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applicationId", r.applicationId());
    out.put("applicationNo", r.applicationNo());
    out.put("reservedMerchantId", r.reservedMerchantId());
    out.put("status", r.status());
    out.put("version", Long.toString(r.version()));
    out.put("currentRevisionId", r.currentRevision().revisionId());
    return out;
  }

  static Map<String, Object> task(ReviewTaskResult t) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applicationId", t.applicationId());
    out.put("taskId", t.taskId());
    out.put("submittedRevisionId", t.submittedRevisionId());
    out.put("status", t.status());
    out.put("version", Long.toString(t.version()));
    out.put("claimedByOperatorId", t.claimedByOperatorId());
    return out;
  }

  static Map<String, Object> decision(DecisionView d) {
    if (d == null) return null;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reviewDecisionId", d.reviewDecisionId());
    out.put("submittedRevisionId", d.submittedRevisionId());
    out.put("decisionType", d.decisionType());
    out.put("opinion", d.opinion());
    out.put("decidedAt", time(d.decidedAt()));
    return out;
  }

  static Map<String, Object> draft(DraftRevisionInput d) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("merchantName", d.merchantName());
    out.put("contactName", d.contactName());
    out.put("contactPhone", d.contactPhone());
    out.put("email", d.email());
    out.put("merchantTypeCode", d.merchantTypeCode());
    out.put("cityCode", d.cityCode());
    out.put("address", d.address());
    out.put("longitude", decimal(d.longitude()));
    out.put("latitude", decimal(d.latitude()));
    out.put("introduction", d.introduction());
    out.put("storePhotoAssetIds", d.storePhotoAssetIds());
    out.put("businessLicenseAssetId", d.businessLicenseAssetId());
    out.put("idCardFrontAssetId", d.idCardFrontAssetId());
    out.put("idCardBackAssetId", d.idCardBackAssetId());
    out.put("industryLicenseAssetId", d.industryLicenseAssetId());
    return out;
  }

  static Map<String, Object> owner(OwnerApplicationDetail a) {
    Map<String, Object> revision = new LinkedHashMap<>();
    revision.put("revisionId", a.currentRevision().revisionId());
    revision.put("revisionNo", a.currentRevision().revisionNo());
    revision.put("draft", draft(a.currentRevision().draft()));
    revision.put("createdAt", time(a.currentRevision().createdAt()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applicationId", a.applicationId());
    out.put("applicationNo", a.applicationNo());
    out.put("reservedMerchantId", a.reservedMerchantId());
    out.put("status", a.status());
    out.put("version", Long.toString(a.version()));
    out.put("currentRevisionId", a.currentRevisionId());
    out.put("currentRevision", revision);
    out.put("submittedAt", time(a.submittedAt()));
    out.put("reviewedAt", time(a.reviewedAt()));
    out.put("latestDecision", decision(a.latestDecision()));
    out.put("subjectVerificationStatus", verification(a.subjectVerificationStatus()));
    return out;
  }

  static Map<String, Object> summary(MerchantApplicationSummary s) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applicationId", s.applicationId());
    out.put("applicationNo", s.applicationNo());
    out.put("reservedMerchantId", s.reservedMerchantId());
    out.put("status", s.status());
    out.put("version", Long.toString(s.version()));
    out.put("merchantName", s.merchantName());
    out.put("merchantTypeCode", s.merchantTypeCode());
    out.put("cityCode", s.cityCode());
    out.put("submittedRevisionId", s.submittedRevisionId());
    out.put("submittedAt", time(s.submittedAt()));
    out.put("subjectVerificationStatus", verification(s.subjectVerificationStatus()));
    return out;
  }

  static Map<String, Object> review(MerchantApplicationReviewDetail d) {
    MerchantApplicationResult a = d.application();
    RevisionView r = a.currentRevision();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("merchantName", r.merchantName());
    snapshot.put("merchantTypeCode", r.merchantTypeCode());
    snapshot.put("cityCode", r.cityCode());
    snapshot.put("address", r.address());
    snapshot.put("longitude", decimal(r.longitude()));
    snapshot.put("latitude", decimal(r.latitude()));
    snapshot.put("introduction", r.introduction());
    snapshot.put("storePhotoAssetIds", r.storePhotoAssetIds());
    snapshot.put("businessLicenseAssetId", r.businessLicenseAssetId());
    snapshot.put("idCardFrontAssetId", r.idCardFrontAssetId());
    snapshot.put("idCardBackAssetId", r.idCardBackAssetId());
    snapshot.put("industryLicenseAssetId", r.industryLicenseAssetId());
    snapshot.put("contactNameMasked", r.contactName());
    snapshot.put("contactPhoneMasked", r.contactPhoneMasked());
    snapshot.put("emailMasked", r.emailMasked());
    Map<String, Object> revision = new LinkedHashMap<>();
    revision.put("revisionId", r.revisionId());
    revision.put("revisionNo", r.revisionNo());
    revision.put("snapshot", snapshot);
    revision.put(
        "materialReferences",
        d.materialReferences().stream()
            .map(
                reference ->
                    Map.of(
                        "materialId", reference.materialId(),
                        "assetId", reference.assetId(),
                        "materialSha256", reference.materialSha256(),
                        "materialType", reference.materialType(),
                        "position", reference.position()))
            .toList());
    revision.put("createdAt", time(r.createdAt()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applicationId", a.applicationId());
    out.put("applicationNo", a.applicationNo());
    out.put("reservedMerchantId", a.reservedMerchantId());
    out.put("status", a.status());
    out.put("version", Long.toString(a.version()));
    out.put("merchantName", r.merchantName());
    out.put("merchantTypeCode", r.merchantTypeCode());
    out.put("cityCode", r.cityCode());
    out.put("submittedRevisionId", d.task().submittedRevisionId());
    out.put("submittedAt", time(a.submittedAt()));
    out.put("subjectVerificationStatus", verification(a.subjectVerificationStatus()));
    out.put("submittedRevision", revision);
    out.put("task", task(d.task()));
    out.put("latestDecision", decision(a.latestDecision()));
    return out;
  }

  private static String verification(String value) {
    return switch (value) {
      case "NOT_STARTED", "SUBJECT_VERIFICATION_PENDING", "PENDING" -> "PENDING";
      case "VERIFIED" -> "VERIFIED";
      default -> throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "申请核验状态暂时不可用");
    };
  }
}
