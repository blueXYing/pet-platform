package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpRequests.*;
import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = "/api/v1/admin/merchant-applications",
    produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public final class MerchantApplicationAdminController {
  private static final Set<String> STATUSES = Set.of("REVIEWING", "APPROVED", "REJECTED");
  private static final Set<String> TYPES =
      Set.of(
          "PET_LIFE_STORE",
          "PET_HOSPITAL",
          "PET_GROOMING",
          "PET_BOARDING",
          "PET_TRAINING",
          "OTHER");
  private final MerchantApplicationCommandApi commands;
  private final MerchantApplicationQueryApi queries;

  public MerchantApplicationAdminController(
      MerchantApplicationCommandApi commands, MerchantApplicationQueryApi queries) {
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
      @RequestParam(required = false) String merchantTypeCode,
      @RequestParam(required = false) String cityCode,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo,
      @RequestParam(required = false) String keyword,
      HttpServletRequest req) {
    onlyParameters(
        req,
        "page",
        "pageSize",
        "status",
        "merchantTypeCode",
        "cityCode",
        "submittedFrom",
        "submittedTo",
        "keyword");
    if (page < 1
        || pageSize < 1
        || pageSize > 100
        || (status != null && !STATUSES.contains(status))
        || (merchantTypeCode != null && !TYPES.contains(merchantTypeCode))
        || (cityCode != null && (cityCode.isEmpty() || cityCode.length() > 32))
        || (keyword != null && (keyword.isEmpty() || keyword.length() > 128))) throw invalid();
    AdminSessionView session = admin(req, "merchant.application.read");
    MerchantApplicationPage result =
        queries.listForReview(
            new MerchantApplicationReviewListQuery(
                page,
                pageSize,
                status,
                merchantTypeCode,
                cityCode,
                submittedFrom == null ? null : timestamp(submittedFrom),
                submittedTo == null ? null : timestamp(submittedTo),
                keyword,
                authorization(session),
                adminQuery(req, session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", result.items().stream().map(MerchantHttpSupport::summary).toList());
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    return envelope(data, req);
  }

  @GetMapping("/{applicationId}")
  Map<String, Object> get(@PathVariable String applicationId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = admin(req, "merchant.application.read");
    return envelope(
        review(
            queries.getForReview(
                new MerchantApplicationReviewQuery(
                    id(applicationId), authorization(session), adminQuery(req, session)))),
        req);
  }

  @PostMapping(value = "/{applicationId}/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> claim(
      @PathVariable String applicationId, @RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    TaskVersion body = decode(json, TaskVersion.class);
    AdminSessionView session = admin(req, "merchant.application.decide");
    ReviewTaskResult result =
        commands.claim(
            new ClaimMerchantApplicationCommand(
                id(applicationId),
                version(body.expectedTaskVersion()),
                authorization(session),
                adminCommand(req, session)));
    return envelope(task(result), req);
  }

  @PostMapping(value = "/{applicationId}/release", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> release(
      @PathVariable String applicationId, @RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    TaskVersion body = decode(json, TaskVersion.class);
    AdminSessionView session = admin(req, "merchant.application.decide");
    ReviewTaskResult result =
        commands.release(
            new ReleaseMerchantApplicationCommand(
                id(applicationId),
                version(body.expectedTaskVersion()),
                authorization(session),
                adminCommand(req, session)));
    return envelope(task(result), req);
  }

  @PostMapping(
      value = "/{applicationId}/manual-verification",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> verify(
      @PathVariable String applicationId, @RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    ManualVerify body = decode(json, ManualVerify.class);
    AdminSessionView session =
        admin(req, "merchant.application.decide", "merchant.identity.reveal");
    MerchantApplicationResult result =
        commands.recordManualVerification(
            new VerifyMerchantSubjectCommand(
                id(applicationId),
                id(body.submissionRevisionId()),
                version(body.expectedVersion()),
                version(body.expectedTaskVersion()),
                body.evidenceItems().stream().map(Evidence::command).toList(),
                body.reason(),
                body.confirmed(),
                authorization(session),
                adminCommand(req, session)));
    return envelope(receipt(result), req);
  }

  @PostMapping(value = "/{applicationId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> decide(
      @PathVariable String applicationId, @RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    Decision body = decode(json, Decision.class);
    AdminSessionView session = admin(req, "merchant.application.decide");
    MerchantApplicationResult result =
        commands.decide(
            new DecideMerchantApplicationCommand(
                id(applicationId),
                body.decisionType(),
                id(body.submissionRevisionId()),
                version(body.expectedVersion()),
                version(body.expectedTaskVersion()),
                body.opinion(),
                body.internalNote(),
                body.confirmed(),
                authorization(session),
                adminCommand(req, session)));
    return envelope(receipt(result), req);
  }
}
