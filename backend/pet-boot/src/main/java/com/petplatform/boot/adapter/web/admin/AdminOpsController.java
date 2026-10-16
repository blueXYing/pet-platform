package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.error;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.flag;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.id;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.instant;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.ok;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.onlyParameters;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.page;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.pageSize;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.requestId;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.session;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.stringBody;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.time;
import static com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.trace;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.BreedMutation;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.BreedStatusCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CategoryMutation;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CategoryStatusCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.UpdateBreedCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.UpdateCategoryCommand;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminAuthorization;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedDetailQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedListQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminCategoryListQuery;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.CreateTemplateCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateStatusCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.UpdateTemplateCommand;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceListQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi;
import com.petplatform.coupon.api.command.AdminCouponCommandApi;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateListQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.TrialCalculationQuery;
import com.petplatform.points.api.query.AdminPointsQueryApi.AdminLedgerListQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract-65 platform ops console face (A-005, HTTP10 §5.8/§5.13): encyclopedia governance
 * (nine routes), coupon template management + instance ledger (nine routes), points ledger and
 * the read-only runtime switch view (two routes) — twenty routes over the community/coupon/
 * points kernels plus the boot environment. Same ADMIN_WEB bearer chain, ALL data scope and the
 * {@code pet.admin.ops.http.enabled} face switch as the contract-63/64 faces — default off.
 * Reads relay kernel-owned projections verbatim; writes take one terminal X-Request-Id (the
 * idempotency binding lives in the owning kernel) and an expectedVersion optimistic guard. No
 * adapter-side fact building, no sensitive material; runtime settings are strictly read-only
 * (no runtime configuration mechanism exists in V1 — CCR-ADM-A005-OPS-001).
 */
@RestController
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.ops.http.enabled"},
    havingValue = "true")
public class AdminOpsController {

  private static final ObjectReader STRICT =
      com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();

  private static final String[] CATEGORY_LIST_PARAMS = {"page", "pageSize"};
  private static final String[] BREED_LIST_PARAMS = {
    "page", "pageSize", "status", "categoryCode", "keyword"
  };
  private static final String[] TEMPLATE_LIST_PARAMS = {"page", "pageSize", "status"};
  private static final String[] TRIAL_PARAMS = {"orderAmount"};
  private static final String[] INSTANCE_LIST_PARAMS = {
    "page", "pageSize", "templateId", "status", "orderId", "createdFrom", "createdTo"
  };
  private static final String[] LEDGER_LIST_PARAMS = {
    "page", "pageSize", "userId", "bizType", "createdFrom", "createdTo"
  };

  /** 21号《部署开关组合清单》登记的装配开关系（phase 为该清单归属域；只读视图，非配置机制）。 */
  private static final String[][] SWITCH_REGISTRY = {
    {"pet.auth.admin.enabled", "ADMIN"},
    {"pet.auth.c.enabled", "C"},
    {"pet.outbox.enabled", "INFRA"},
    {"pet.notification.delivery.enabled", "INFRA"},
    {"pet.merchant.application.enabled", "MERCHANT"},
    {"pet.merchant.subject.enabled", "MERCHANT"},
    {"pet.merchant.protection.enabled", "MERCHANT"},
    {"pet.merchant.staff.enabled", "MERCHANT"},
    {"pet.merchant.staff-identity.enabled", "MERCHANT"},
    {"pet.merchant.staff-member.enabled", "MERCHANT"},
    {"pet.service.query.enabled", "CATALOG"},
    {"pet.service.command.enabled", "CATALOG"},
    {"pet.service.cover-signing.enabled", "CATALOG"},
    {"pet.store.query.enabled", "CATALOG"},
    {"pet.private-assets.enabled", "CATALOG"},
    {"pet.schedule.protection.enabled", "SCHEDULE"},
    {"pet.schedule.command.enabled", "SCHEDULE"},
    {"pet.schedule.command.http.enabled", "SCHEDULE"},
    {"pet.order.creation.enabled", "ORDER"},
    {"pet.order.expiry.enabled", "ORDER"},
    {"pet.order.expiry.worker.enabled", "ORDER"},
    {"pet.order.auto-confirm.enabled", "ORDER"},
    {"pet.order.auto-confirm.worker.enabled", "ORDER"},
    {"pet.order.auto-confirm.repair.enabled", "ORDER"},
    {"pet.order.merchant.enabled", "ORDER"},
    {"pet.order.merchant.http.enabled", "ORDER"},
    {"pet.order.merchant.worker.enabled", "ORDER"},
    {"pet.order.reschedule.enabled", "ORDER"},
    {"pet.order.reschedule.http.enabled", "ORDER"},
    {"pet.payment.foundation.enabled", "PAYMENT"},
    {"pet.payment.dispatch.enabled", "PAYMENT"},
    {"pet.refund.late.enabled", "REFUND"},
    {"pet.refund.late.worker.enabled", "REFUND"},
    {"pet.refund.application.enabled", "REFUND"},
    {"pet.refund.application.worker.enabled", "REFUND"},
    {"pet.refund.application.http.enabled", "REFUND"},
    {"pet.refund.merchant.http.enabled", "REFUND"},
    {"pet.aftersale.enabled", "AFTERSALE"},
    {"pet.aftersale.worker.enabled", "AFTERSALE"},
    {"pet.aftersale.http.enabled", "AFTERSALE"},
    {"pet.aftersale.refund.enabled", "AFTERSALE"},
    {"pet.verification.credential.enabled", "VERIFICATION"},
    {"pet.verification.credential.http.enabled", "VERIFICATION"},
    {"pet.verification.staff-identity.enabled", "VERIFICATION"},
    {"pet.verification.completion.enabled", "VERIFICATION"},
    {"pet.verification.completion.http.enabled", "VERIFICATION"},
    {"pet.review.enabled", "REVIEW"},
    {"pet.review.http.enabled", "REVIEW"},
    {"pet.review.appeal.enabled", "REVIEW"},
    {"pet.review.appeal.http.enabled", "REVIEW"},
    {"pet.points.task.enabled", "POINTS"},
    {"pet.points.task.http.enabled", "POINTS"},
    {"pet.favtrack.http.enabled", "C"},
    {"pet.encyclopedia.query.enabled", "COMMUNITY"},
    {"pet.coupon.lifecycle.enabled", "COUPON"},
    {"pet.coupon.grant.enabled", "COUPON"},
    {"pet.coupon.void.enabled", "COUPON"},
    {"pet.admin.trade.http.enabled", "ADMIN"},
    {"pet.admin.trade.worker.enabled", "ADMIN"},
    {"pet.admin.governance.http.enabled", "ADMIN"},
    {"pet.admin.ops.http.enabled", "ADMIN"}
  };

  private final AdminEncyclopediaQueryApi encyclopediaQuery;
  private final AdminEncyclopediaCommandApi encyclopediaCommands;
  private final AdminCouponQueryApi couponQuery;
  private final AdminCouponCommandApi couponCommands;
  private final com.petplatform.points.api.query.AdminPointsQueryApi points;
  private final Environment environment;

  public AdminOpsController(
      AdminEncyclopediaQueryApi encyclopediaQuery,
      AdminEncyclopediaCommandApi encyclopediaCommands,
      AdminCouponQueryApi couponQuery,
      AdminCouponCommandApi couponCommands,
      com.petplatform.points.api.query.AdminPointsQueryApi points,
      Environment environment) {
    this.encyclopediaQuery = encyclopediaQuery;
    this.encyclopediaCommands = encyclopediaCommands;
    this.couponQuery = couponQuery;
    this.couponCommands = couponCommands;
    this.points = points;
    this.environment = environment;
  }

  @ModelAttribute
  void noStore(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  // ------------------------------------------------------------- encyclopedia

  @GetMapping(value = "/api/v1/admin/encyclopedia/categories", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> listCategories(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, CATEGORY_LIST_PARAMS);
    AdminSessionView session = session(req, "encyclopedia.read");
    var result =
        encyclopediaQuery.listCategories(
            new AdminCategoryListQuery(
                page(params.get("page")), pageSize(params.get("pageSize")),
                query(req, session), authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                row -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put("categoryCode", row.categoryCode());
                  item.put("categoryName", row.categoryName());
                  item.put("status", row.status());
                  item.put("sortOrder", row.sortNo());
                  item.put("breedCount", row.breedCount());
                  item.put("publishedBreedCount", row.publishedBreedCount());
                  item.put("version", Long.toString(row.version()));
                  item.put("createdAt", time(row.createdAt()));
                  item.put("updatedAt", time(row.updatedAt()));
                  return item;
                })
            .toList());
    return ok(data, req);
  }

  @PutMapping(value = "/api/v1/admin/encyclopedia/categories/{categoryCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> updateCategory(
      @PathVariable String categoryCode, @RequestBody String raw, HttpServletRequest req) {
    noQuery(req);
    AdminSessionView session = session(req, "encyclopedia.manage");
    JsonNode body = strictObject(raw, "categoryName", "sortNo", "expectedVersion");
    var receipt =
        encyclopediaCommands.updateCategory(
            new UpdateCategoryCommand(
                categoryText(categoryCode),
                text(body, "categoryName"),
                intField(body, "sortNo"),
                longField(body, "expectedVersion"),
                command(req, session),
                encyclopediaCommandAuthorization(session)));
    return ok(categoryFields(receipt), req);
  }

  @PostMapping("/api/v1/admin/encyclopedia/categories/{categoryCode}/enable")
  Map<String, Object> enableCategory(@PathVariable String categoryCode, HttpServletRequest req) {
    noQuery(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView session = session(req, "encyclopedia.manage");
    JsonNode body = strictObject(bodyOrNull(req), "expectedVersion");
    var receipt =
        encyclopediaCommands.enableCategory(
            new CategoryStatusCommand(
                categoryText(categoryCode), longField(body, "expectedVersion"),
                command(req, session), encyclopediaCommandAuthorization(session)));
    return ok(categoryFields(receipt), req);
  }

  @PostMapping("/api/v1/admin/encyclopedia/categories/{categoryCode}/disable")
  Map<String, Object> disableCategory(@PathVariable String categoryCode, HttpServletRequest req) {
    noQuery(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView session = session(req, "encyclopedia.manage");
    JsonNode body = strictObject(bodyOrNull(req), "expectedVersion");
    var receipt =
        encyclopediaCommands.disableCategory(
            new CategoryStatusCommand(
                categoryText(categoryCode), longField(body, "expectedVersion"),
                command(req, session), encyclopediaCommandAuthorization(session)));
    return ok(categoryFields(receipt), req);
  }

  @GetMapping(value = "/api/v1/admin/encyclopedia/breeds", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> listBreeds(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, BREED_LIST_PARAMS);
    AdminSessionView session = session(req, "encyclopedia.read");
    var result =
        encyclopediaQuery.listBreeds(
            new AdminBreedListQuery(
                params.get("status"), params.get("categoryCode"), keyword(params.get("keyword")),
                page(params.get("page")), pageSize(params.get("pageSize")),
                query(req, session), authorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream().map(AdminOpsController::breedFields).toList());
    return ok(data, req);
  }

  @GetMapping(value = "/api/v1/admin/encyclopedia/breeds/{breedId}", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> getBreed(@PathVariable String breedId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "breed.read");
    var detail =
        encyclopediaQuery.getBreed(
            new AdminBreedDetailQuery(
                id(breedId), query(req, session), authorization(session)));
    Map<String, Object> data = breedDetailFields(detail);
    return ok(data, req);
  }

  @PutMapping(value = "/api/v1/admin/encyclopedia/breeds/{breedId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> updateBreed(
      @PathVariable String breedId, @RequestBody String raw, HttpServletRequest req) {
    noQuery(req);
    AdminSessionView session = session(req, "breed.manage");
    JsonNode body =
        strictObject(raw, "summary", "coverUrl", "bodyType", "aliases", "tags", "sortNo",
            "expectedVersion");
    var receipt =
        encyclopediaCommands.updateBreed(
            new UpdateBreedCommand(
                id(breedId),
                text(body, "summary"),
                optionalText(body, "coverUrl"),
                optionalText(body, "bodyType"),
                stringArray(body, "aliases"),
                stringArray(body, "tags"),
                intField(body, "sortNo"),
                longField(body, "expectedVersion"),
                command(req, session),
                encyclopediaCommandAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("breedId", receipt.breedId());
    data.put("name", receipt.name());
    data.put("status", receipt.status());
    data.put("sortOrder", receipt.sortNo());
    data.put("version", Long.toString(receipt.version()));
    data.put("occurredAt", time(receipt.occurredAt()));
    return ok(data, req);
  }

  @PostMapping("/api/v1/admin/encyclopedia/breeds/{breedId}/publish")
  Map<String, Object> publishBreed(@PathVariable String breedId, HttpServletRequest req) {
    noQuery(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView session = session(req, "encyclopedia.publish");
    JsonNode body = strictObject(bodyOrNull(req), "expectedVersion");
    var receipt =
        encyclopediaCommands.publishBreed(
            new BreedStatusCommand(
                id(breedId), longField(body, "expectedVersion"),
                command(req, session), encyclopediaCommandAuthorization(session)));
    return ok(breedMutationFields(receipt), req);
  }

  @PostMapping("/api/v1/admin/encyclopedia/breeds/{breedId}/offline")
  Map<String, Object> offlineBreed(@PathVariable String breedId, HttpServletRequest req) {
    noQuery(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView session = session(req, "encyclopedia.publish");
    JsonNode body = strictObject(bodyOrNull(req), "expectedVersion");
    var receipt =
        encyclopediaCommands.offlineBreed(
            new BreedStatusCommand(
                id(breedId), longField(body, "expectedVersion"),
                command(req, session), encyclopediaCommandAuthorization(session)));
    return ok(breedMutationFields(receipt), req);
  }

  // ------------------------------------------------------------------- coupon

  @GetMapping(value = "/api/v1/admin/coupon-templates", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> listTemplates(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, TEMPLATE_LIST_PARAMS);
    AdminSessionView session = session(req, "coupon.template.read");
    var result =
        couponQuery.listTemplates(
            new AdminTemplateListQuery(
                params.get("status"), page(params.get("page")), pageSize(params.get("pageSize")),
                query(req, session), couponAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream().map(AdminOpsController::templateFields).toList());
    return ok(data, req);
  }

  @GetMapping(value = "/api/v1/admin/coupon-templates/{templateId}", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> getTemplate(@PathVariable String templateId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "coupon.template.read");
    var detail =
        couponQuery.getTemplate(
            new AdminTemplateQuery(id(templateId), query(req, session),
                couponAuthorization(session)));
    Map<String, Object> data =
        templateFields(
            new com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateSummary(
                detail.templateId(), detail.name(), detail.status(), detail.amountOff(),
                detail.thresholdAmount(), detail.validStartAt(), detail.validEndAt(),
                detail.totalStock(), detail.issuedCount(), detail.version(), detail.createdAt(),
                detail.updatedAt()));
    data.put("scopeSummary", detail.scopeSummary());
    data.put("typeLabel", detail.typeLabel());
    return ok(data, req);
  }

  @GetMapping(value = "/api/v1/admin/coupon-templates/{templateId}/trial-calculation", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> trialCalculation(
      @PathVariable String templateId,
      @RequestParam java.util.Map<String, String> params,
      HttpServletRequest req) {
    onlyParameters(req, TRIAL_PARAMS);
    AdminSessionView session = session(req, "coupon.template.read");
    String raw = params.get("orderAmount");
    if (raw == null || !raw.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) {
      throw AdminTradeHttpSupport.invalid();
    }
    var result =
        couponQuery.trialCalculation(
            new TrialCalculationQuery(
                id(templateId), new BigDecimal(raw).setScale(2), query(req, session),
                couponAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("templateId", result.templateId());
    data.put("orderAmount", money(result.orderAmount()));
    data.put("thresholdAmount", result.thresholdAmount() == null ? null : money(result.thresholdAmount()));
    data.put("discountAmount", result.discountAmount() == null ? null : money(result.discountAmount()));
    data.put("payAmount", result.payAmount() == null ? null : money(result.payAmount()));
    data.put("eligible", result.eligible());
    data.put("ineligibleReason", result.ineligibleReason());
    return ok(data, req);
  }

  @PostMapping(value = "/api/v1/admin/coupon-templates", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> createTemplate(@RequestBody String raw, HttpServletRequest req) {
    noQuery(req);
    AdminSessionView session = session(req, "coupon.template.manage");
    JsonNode body =
        strictObject(raw, "name", "amountOff", "thresholdAmount", "validStartAt", "validEndAt",
            "scopeSummary", "typeLabel");
    var receipt =
        couponCommands.createTemplate(
            new CreateTemplateCommand(
                text(body, "name"),
                moneyField(body, "amountOff", false),
                moneyField(body, "thresholdAmount", true),
                instantField(body, "validStartAt"),
                instantField(body, "validEndAt"),
                optionalText(body, "scopeSummary"),
                optionalText(body, "typeLabel"),
                command(req, session),
                couponCommandAuthorization(session)));
    return ok(templateMutationFields(receipt), req);
  }

  @PutMapping(value = "/api/v1/admin/coupon-templates/{templateId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> updateTemplate(
      @PathVariable String templateId, @RequestBody String raw, HttpServletRequest req) {
    noQuery(req);
    AdminSessionView session = session(req, "coupon.template.manage");
    JsonNode body =
        strictObject(raw, "name", "amountOff", "thresholdAmount", "validStartAt", "validEndAt",
            "scopeSummary", "typeLabel", "expectedVersion");
    var receipt =
        couponCommands.updateTemplate(
            new UpdateTemplateCommand(
                id(templateId),
                text(body, "name"),
                moneyField(body, "amountOff", false),
                moneyField(body, "thresholdAmount", true),
                instantField(body, "validStartAt"),
                instantField(body, "validEndAt"),
                optionalText(body, "scopeSummary"),
                optionalText(body, "typeLabel"),
                longField(body, "expectedVersion"),
                command(req, session),
                couponCommandAuthorization(session)));
    return ok(templateMutationFields(receipt), req);
  }

  @PostMapping("/api/v1/admin/coupon-templates/{templateId}/enable")
  Map<String, Object> enableTemplate(@PathVariable String templateId, HttpServletRequest req) {
    noQuery(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView session = session(req, "coupon.template.manage");
    JsonNode body = strictObject(bodyOrNull(req), "expectedVersion");
    var receipt =
        couponCommands.enableTemplate(
            new TemplateStatusCommand(
                id(templateId), longField(body, "expectedVersion"),
                command(req, session), couponCommandAuthorization(session)));
    return ok(templateMutationFields(receipt), req);
  }

  @PostMapping("/api/v1/admin/coupon-templates/{templateId}/offline")
  Map<String, Object> offlineTemplate(@PathVariable String templateId, HttpServletRequest req) {
    noQuery(req);
    AdminTradeHttpSupport.noBody(req);
    AdminSessionView session = session(req, "coupon.template.manage");
    JsonNode body = strictObject(bodyOrNull(req), "expectedVersion");
    var receipt =
        couponCommands.offlineTemplate(
            new TemplateStatusCommand(
                id(templateId), longField(body, "expectedVersion"),
                command(req, session), couponCommandAuthorization(session)));
    return ok(templateMutationFields(receipt), req);
  }

  @GetMapping(value = "/api/v1/admin/coupon-instances", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> listInstances(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, INSTANCE_LIST_PARAMS);
    AdminSessionView session = session(req, "coupon.instance.read");
    var result =
        couponQuery.listInstances(
            new AdminInstanceListQuery(
                com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.optionalId(
                    params.get("templateId")),
                params.get("status"),
                com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.optionalId(
                    params.get("orderId")),
                instant(params.get("createdFrom")),
                instant(params.get("createdTo")),
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                couponAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream().map(AdminOpsController::instanceFields).toList());
    return ok(data, req);
  }

  @GetMapping(value = "/api/v1/admin/coupon-instances/{instanceId}", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> getInstance(@PathVariable String instanceId, HttpServletRequest req) {
    onlyParameters(req);
    AdminSessionView session = session(req, "coupon.instance.read");
    var detail =
        couponQuery.getInstance(
            new AdminInstanceQuery(id(instanceId), query(req, session),
                couponAuthorization(session)));
    Map<String, Object> data =
        instanceFields(
            new com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceSummary(
                detail.instanceId(), detail.templateId(), detail.templateName(), detail.userId(),
                detail.status(), detail.amountOff(), detail.thresholdAmount(),
                detail.originalExpireAt(), detail.expireAt(), detail.orderId(), detail.frozenAt(),
                detail.usedAt(), detail.createdAt()));
    data.put("freezeExpireAt", time(detail.freezeExpireAt()));
    data.put("updatedAt", time(detail.updatedAt()));
    data.put(
        "ledger",
        detail.ledger().stream()
            .map(
                row -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put("ledgerId", row.ledgerId());
                  item.put("action", row.action());
                  item.put("fromStatus", row.fromStatus());
                  item.put("toStatus", row.toStatus());
                  item.put("orderId", row.orderId());
                  item.put("requestId", row.requestId());
                  item.put("remark", row.remark());
                  item.put("createdAt", time(row.createdAt()));
                  return item;
                })
            .toList());
    return ok(data, req);
  }

  // ------------------------------------------------------------------- ledger

  @GetMapping(value = "/api/v1/admin/points/ledger", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> listPointsLedger(
      @RequestParam java.util.Map<String, String> params, HttpServletRequest req) {
    onlyParameters(req, LEDGER_LIST_PARAMS);
    AdminSessionView session = session(req, "points.ledger.read");
    var result =
        points.queryLedger(
            new AdminLedgerListQuery(
                com.petplatform.boot.adapter.web.admin.AdminTradeHttpSupport.optionalId(
                    params.get("userId")),
                params.get("bizType"),
                instant(params.get("createdFrom")),
                instant(params.get("createdTo")),
                page(params.get("page")),
                pageSize(params.get("pageSize")),
                query(req, session),
                pointsAuthorization(session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    data.put(
        "items",
        result.items().stream()
            .map(
                row -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put("ledgerId", row.ledgerId());
                  item.put("userId", row.userId());
                  item.put("bizType", row.bizType());
                  item.put("delta", row.delta());
                  item.put("balanceAfter", row.balanceAfter());
                  item.put("orderId", row.orderId());
                  item.put("createdAt", time(row.createdAt()));
                  return item;
                })
            .toList());
    return ok(data, req);
  }

  // ----------------------------------------------------------------- settings

  @GetMapping(value = "/api/v1/admin/settings/runtime", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> runtimeSettings(HttpServletRequest req) {
    onlyParameters(req);
    session(req, "system.parameter.read");
    List<Map<String, Object>> switches = new ArrayList<>();
    for (String[] entry : SWITCH_REGISTRY) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", entry[0]);
      row.put("enabled", environment.getProperty(entry[0], Boolean.class, Boolean.FALSE));
      row.put("phase", entry[1]);
      switches.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("switches", switches);
    data.put("readOnly", true);
    return ok(data, req);
  }

  // ------------------------------------------------------------------ support

  private static Map<String, Object> categoryFields(CategoryMutation receipt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("categoryCode", receipt.categoryCode());
    data.put("categoryName", receipt.categoryName());
    data.put("status", receipt.status());
    data.put("sortOrder", receipt.sortNo());
    data.put("version", Long.toString(receipt.version()));
    data.put("occurredAt", time(receipt.occurredAt()));
    return data;
  }

  private static Map<String, Object> breedFields(
      com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedCard row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("breedId", row.breedId());
    data.put("categoryCode", row.categoryCode());
    data.put("categoryName", row.categoryName());
    data.put("name", row.name());
    data.put("aliases", row.aliases());
    data.put("tags", row.tags());
    data.put("summary", row.summary());
    data.put("coverUrl", row.coverUrl());
    data.put("status", row.status());
    data.put("sortNo", row.sortNo());
    data.put("version", Long.toString(row.version()));
    data.put("updatedAt", time(row.updatedAt()));
    return data;
  }

  private static Map<String, Object> breedDetailFields(
      com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedDetail row) {
    Map<String, Object> data = breedFields(new com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedCard(
        row.breedId(), row.categoryCode(), row.categoryName(), row.name(), row.aliases(),
        row.tags(), row.summary(), row.coverUrl(), row.status(), row.sortNo(), row.version(),
        row.updatedAt()));
    data.put("bodyType", row.bodyType());
    data.put("descriptionHtml", row.descriptionHtml());
    data.put("careAdviceHtml", row.careAdviceHtml());
    data.put("healthTipsHtml", row.healthTipsHtml());
    data.put("createdAt", time(row.createdAt()));
    return data;
  }

  private static Map<String, Object> breedMutationFields(BreedMutation receipt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("breedId", receipt.breedId());
    data.put("name", receipt.name());
    data.put("status", receipt.status());
    data.put("sortOrder", receipt.sortNo());
    data.put("version", Long.toString(receipt.version()));
    data.put("occurredAt", time(receipt.occurredAt()));
    return data;
  }

  private static Map<String, Object> templateFields(
      com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateSummary row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("templateId", row.templateId());
    data.put("name", row.name());
    data.put("status", row.status());
    data.put("amountOff", money(row.amountOff()));
    data.put("thresholdAmount", row.thresholdAmount() == null ? null : money(row.thresholdAmount()));
    data.put("validStartAt", time(row.validStartAt()));
    data.put("validEndAt", time(row.validEndAt()));
    data.put("totalStock", row.totalStock() == null ? null : Long.toString(row.totalStock()));
    data.put("issuedCount", Long.toString(row.issuedCount()));
    data.put("version", Long.toString(row.version()));
    data.put("createdAt", time(row.createdAt()));
    data.put("updatedAt", time(row.updatedAt()));
    return data;
  }

  private static Map<String, Object> templateMutationFields(
      com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateMutation receipt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("templateId", receipt.templateId());
    data.put("name", receipt.name());
    data.put("status", receipt.status());
    data.put("version", Long.toString(receipt.version()));
    data.put("occurredAt", time(receipt.occurredAt()));
    return data;
  }

  private static Map<String, Object> instanceFields(
      com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceSummary row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("instanceId", row.instanceId());
    data.put("templateId", row.templateId());
    data.put("templateName", row.templateName());
    data.put("userId", row.userId());
    data.put("status", row.status());
    data.put("amountOff", money(row.amountOff()));
    data.put("thresholdAmount", row.thresholdAmount() == null ? null : money(row.thresholdAmount()));
    data.put("originalExpireAt", time(row.originalExpireAt()));
    data.put("expireAt", time(row.expireAt()));
    data.put("orderId", row.orderId());
    data.put("frozenAt", time(row.frozenAt()));
    data.put("usedAt", time(row.usedAt()));
    data.put("createdAt", time(row.createdAt()));
    return data;
  }

  private static QueryContext query(HttpServletRequest req, AdminSessionView session) {
    return new QueryContext(trace(req), OperatorType.PLATFORM_OPERATOR,
        session.principal().operatorId());
  }

  private static CommandContext command(HttpServletRequest req, AdminSessionView session) {
    return new CommandContext(requestId(req), trace(req), OperatorType.PLATFORM_OPERATOR,
        session.principal().operatorId(), "ADMIN_WEB");
  }

  private static AdminAuthorization authorization(AdminSessionView session) {
    return new AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization
          encyclopediaCommandAuthorization(AdminSessionView session) {
    return new com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization
          couponCommandAuthorization(AdminSessionView session) {
    return new com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization
          couponAuthorization(AdminSessionView session) {
    return new com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static com.petplatform.points.api.query.AdminPointsQueryApi.AdminAuthorization
          pointsAuthorization(AdminSessionView session) {
    return new com.petplatform.points.api.query.AdminPointsQueryApi.AdminAuthorization(
        session.principal().sessionId(), session.principal().sessionGeneration());
  }

  private static String money(java.math.BigDecimal value) {
    return value == null ? null : value.setScale(2).toPlainString();
  }

  private static void noQuery(HttpServletRequest req) {
    if (req.getParameterMap() != null && !req.getParameterMap().isEmpty()) {
      throw AdminTradeHttpSupport.invalid();
    }
  }

  /** Status-route bodies are optional; absent bodies become strict empty objects. */
  private static String bodyOrNull(HttpServletRequest req) {
    try {
      byte[] raw = req.getInputStream().readAllBytes();
      return raw.length == 0 ? "{}" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException failure) {
      throw AdminTradeHttpSupport.invalid();
    }
  }

  /** Unknown fields, explicit nulls, duplicate keys and trailing tokens are all 400. */
  private static JsonNode strictObject(String raw, String... fields) {
    try {
      JsonNode root = STRICT.readTree(raw);
      if (root == null || !root.isObject()) throw AdminTradeHttpSupport.invalid();
      var allowed = Set.of(fields);
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

  private static String optionalText(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw AdminTradeHttpSupport.invalid();
    return value.textValue();
  }

  private static int intField(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isInt()) throw AdminTradeHttpSupport.invalid();
    return value.intValue();
  }

  private static long longField(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
      throw AdminTradeHttpSupport.invalid();
    }
    return value.longValue();
  }

  private static BigDecimal moneyField(JsonNode body, String field, boolean optional) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      if (optional) return null;
      throw AdminTradeHttpSupport.invalid();
    }
    if (!value.isNumber()) throw AdminTradeHttpSupport.invalid();
    try {
      return new BigDecimal(value.stringValue()).setScale(2, java.math.RoundingMode.UNNECESSARY);
    } catch (NumberFormatException bad) {
      throw AdminTradeHttpSupport.invalid();
    }
  }

  private static OffsetDateTime instantField(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isTextual()) throw AdminTradeHttpSupport.invalid();
    try {
      return OffsetDateTime.parse(value.textValue());
    } catch (RuntimeException bad) {
      throw AdminTradeHttpSupport.invalid();
    }
  }

  private static List<String> stringArray(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) return List.of();
    if (!value.isArray()) throw AdminTradeHttpSupport.invalid();
    List<String> items = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isTextual()) throw AdminTradeHttpSupport.invalid();
      items.add(item.textValue());
    }
    return items;
  }

  private static String keyword(String value) {
    if (value == null) return null;
    int length = value.codePointCount(0, value.length());
    if (length < 1 || length > 40 || value.isBlank()
        || value.indexOf('%') >= 0 || value.indexOf('_') >= 0 || value.indexOf('\\') >= 0) {
      throw AdminTradeHttpSupport.invalid();
    }
    return value;
  }

  private static String categoryText(String categoryCode) {
    if (categoryCode == null || !categoryCode.matches("[A-Z]{2,16}")) {
      throw AdminTradeHttpSupport.invalid();
    }
    return categoryCode;
  }

  private static boolean flag(String value) {
    return AdminTradeHttpSupport.flag(value);
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
