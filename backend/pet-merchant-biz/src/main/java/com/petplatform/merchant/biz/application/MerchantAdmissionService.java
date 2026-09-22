package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreReadEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Owner-scoped workbench admission composition (HTTP10 admission matrix, frozen by
 * CCR-W2-ADMISSION-001). Fail-closed: unreadable or contradictory facts are 503, never a
 * synthesized DENIED; admission is an entry hint only and never replaces per-command checks.
 */
public final class MerchantAdmissionService {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private static final Set<String> MERCHANT_STATUSES =
      Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
  private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
  private static final Set<String> APPLICATION_STATUSES =
      Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
  private static final List<String> ALLOWED_ACTIONS =
      List.of("merchant.aftersale.read", "merchant.order.read", "merchant.penalty.read",
          "merchant.schedule.manage", "merchant.service.manage", "merchant.staff.manage");
  private static final List<String> OFFLINE_LIMITED_ACTIONS =
      List.of("merchant.aftersale.read", "merchant.aftersale.respond", "merchant.order.fulfill",
          "merchant.order.read", "merchant.penalty.appeal", "merchant.penalty.read",
          "merchant.refund.handle");
  private static final List<String> FROZEN_LIMITED_ACTIONS =
      List.of("merchant.aftersale.read", "merchant.order.read", "merchant.penalty.appeal",
          "merchant.penalty.read");
  private static final List<MerchantAdmissionDTO.AdmissionStep> NO_STEPS = List.of();
  private static final List<MerchantAdmissionDTO.AdmissionStep> VIEW_APPLICATION_STEPS =
      List.of(new MerchantAdmissionDTO.AdmissionStep("VIEW_APPLICATION"));
  private static final List<MerchantAdmissionDTO.AdmissionStep> COMPLETE_SIGNING_STEPS =
      List.of(new MerchantAdmissionDTO.AdmissionStep("COMPLETE_SIGNING"));
  private static final List<MerchantAdmissionDTO.AdmissionStep> OFFLINE_STEPS = List.of(
      new MerchantAdmissionDTO.AdmissionStep("VIEW_AFTERSALES"),
      new MerchantAdmissionDTO.AdmissionStep("VIEW_EXISTING_ORDERS"));
  private static final List<MerchantAdmissionDTO.AdmissionStep> FROZEN_STEPS = List.of(
      new MerchantAdmissionDTO.AdmissionStep("APPEAL"),
      new MerchantAdmissionDTO.AdmissionStep("VIEW_AFTERSALES"),
      new MerchantAdmissionDTO.AdmissionStep("VIEW_EXISTING_ORDERS"));

  private final MerchantReadStore store;
  private final ApplicationReviewFactsReader applications;
  private final MerchantAgreementStore agreements;
  private final Clock clock;

  public MerchantAdmissionService(
      MerchantReadStore store,
      ApplicationReviewFactsReader applications,
      MerchantAgreementStore agreements,
      Clock clock
  ) {
    this.store = Objects.requireNonNull(store, "store is required");
    this.applications = Objects.requireNonNull(applications, "applications is required");
    this.agreements = Objects.requireNonNull(agreements, "agreements is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
    if (query == null) invalid("query is required");
    int page = query.page();
    int pageSize = query.pageSize();
    if (page < 1 || page > 10_000) invalid("page is invalid");
    if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
    long ownerUserId = ownerUserId(query.context());
    return store.read(mapper -> {
      long total = mapper.countOwnedStores(ownerUserId);
      List<MerchantStoreReadEntity> rows =
          mapper.selectOwnedStores(ownerUserId, pageSize, (int) ((page - 1L) * pageSize));
      List<MerchantMembershipDTO> items = new ArrayList<>(rows.size());
      for (MerchantStoreReadEntity row : rows) {
        validateStoreRow(row, ownerUserId);
        items.add(new MerchantMembershipDTO(
            IDS.toApi(positive(row.getMerchantId(), "merchant id")),
            text(row.getMerchantName(), 128, "merchant name"),
            IDS.toApi(positive(row.getStoreId(), "store id")),
            text(row.getStoreName(), 128, "store name"),
            "OWNER"));
      }
      return new MerchantMembershipPageDTO(List.copyOf(items), page, pageSize, total);
    });
  }

  public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
    if (query == null) invalid("query is required");
    long merchantId = targetId(query.merchantId(), "merchantId");
    long storeId = targetId(query.storeId(), "storeId");
    long ownerUserId = ownerUserId(query.context());
    return store.read(mapper -> {
      // Ownership first; unowned candidates are anti-enumeration 404 like the agreement surface.
      MerchantStoreReadEntity row = mapper.selectOwnedStore(storeId, ownerUserId);
      if (row == null) notFound();
      validateStoreRow(row, ownerUserId);
      if (row.getMerchantId() != merchantId) notFound();
      String merchantStatus = row.getMerchantStatus();
      String storeStatus = row.getStoreStatus();
      if (!MERCHANT_STATUSES.contains(merchantStatus) || !STORE_STATUSES.contains(storeStatus)) {
        unavailable("merchant or store status is unknown");
      }
      ApplicationReviewFactsReader.Facts application = readApplicationFacts(merchantId);
      String applicationStatus = application.applicationStatus();
      if (!APPLICATION_STATUSES.contains(applicationStatus)) {
        unavailable("application status is unknown");
      }
      String signingStatus = readSigningStatus(merchantId);
      String admission;
      List<String> reasonCodes;
      List<String> allowedActions;
      List<MerchantAdmissionDTO.AdmissionStep> nextSteps;
      if (!"APPROVED".equals(applicationStatus)) {
        admission = "DENIED";
        reasonCodes = List.of(applicationReason(applicationStatus));
        allowedActions = List.of();
        nextSteps = VIEW_APPLICATION_STEPS;
      } else if (!"SIGNED".equals(signingStatus)) {
        admission = "DENIED";
        reasonCodes = List.of("SIGNING_REQUIRED");
        allowedActions = List.of();
        nextSteps = COMPLETE_SIGNING_STEPS;
      } else if ("APPLYING".equals(merchantStatus) || "CANCELED".equals(merchantStatus)) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
            "merchant status contradicts the approved and signed facts");
      } else {
        // Mixed statuses report every observed cause; frozen is the stricter limited shape.
        List<String> codes = new ArrayList<>(4);
        boolean offline = "OFFLINE".equals(merchantStatus) || "OFFLINE".equals(storeStatus);
        boolean frozen = "FROZEN".equals(merchantStatus) || "FROZEN".equals(storeStatus);
        if ("OFFLINE".equals(merchantStatus)) codes.add("MERCHANT_OFFLINE");
        if ("OFFLINE".equals(storeStatus)) codes.add("STORE_OFFLINE");
        if ("FROZEN".equals(merchantStatus)) codes.add("MERCHANT_FROZEN");
        if ("FROZEN".equals(storeStatus)) codes.add("STORE_FROZEN");
        if (offline || frozen) {
          admission = "LIMITED";
          reasonCodes = List.copyOf(codes);
          allowedActions = frozen ? FROZEN_LIMITED_ACTIONS : OFFLINE_LIMITED_ACTIONS;
          nextSteps = frozen ? FROZEN_STEPS : OFFLINE_STEPS;
        } else {
          admission = "ALLOWED";
          reasonCodes = List.of();
          allowedActions = ALLOWED_ACTIONS;
          nextSteps = NO_STEPS;
        }
      }
      OffsetDateTime checkedAt =
          OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
      return new MerchantAdmissionDTO(
          IDS.toApi(merchantId), IDS.toApi(storeId), "OWNER", admission, checkedAt,
          authzVersion(merchantStatus, storeStatus, applicationStatus, signingStatus,
              row.getStoreVersion() == null ? 0L : row.getStoreVersion()),
          new MerchantAdmissionDTO.ApplicationFact(applicationStatus),
          new MerchantAdmissionDTO.SigningFact(signingStatus),
          storeStatus, merchantStatus, null,
          allowedActions, reasonCodes, nextSteps);
    });
  }

  private ApplicationReviewFactsReader.Facts readApplicationFacts(long merchantId) {
    ApplicationReviewFactsReader.Facts facts;
    try {
      facts = applications.read(merchantId);
    } catch (RuntimeException failure) {
      unavailable("application review facts are unavailable");
      return null;
    }
    if (facts == null || facts.applicationStatus() == null) {
      unavailable("application review facts are missing");
    }
    return facts;
  }

  private String readSigningStatus(long merchantId) {
    return agreements.joinCurrentTransaction(mapper -> {
      List<MerchantAgreementDocumentEntity> accepted =
          MerchantAgreementService.acceptedAgreements(mapper, merchantId, false);
      if (accepted.isEmpty()) return "NOT_SIGNED";
      MerchantAgreementService.validateDocument(accepted.getFirst(), true);
      return "SIGNED";
    });
  }

  private static String applicationReason(String applicationStatus) {
    return switch (applicationStatus) {
      case "DRAFT" -> "APPLICATION_DRAFT";
      case "REVIEWING" -> "APPLICATION_PENDING";
      case "REJECTED" -> "APPLICATION_REJECTED";
      default -> "NO_APPLICATION";
    };
  }

  private static String authzVersion(
      String merchantStatus, String storeStatus, String applicationStatus,
      String signingStatus, long storeVersion) {
    String material = "OWNER|" + merchantStatus + "|" + storeStatus + "|" + applicationStatus
        + "|" + signingStatus + "|null|" + storeVersion;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int index = 0; index < 8; index++) hex.append(String.format("%02x", digest[index]));
      return hex.toString();
    } catch (Exception unavailable) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "authz version unavailable");
    }
  }

  private static void validateStoreRow(MerchantStoreReadEntity row, long ownerUserId) {
    positive(row.getMerchantId(), "merchant id");
    positive(row.getStoreId(), "store id");
    if (row.getOwnerUserId() == null || row.getOwnerUserId() != ownerUserId) {
      unavailable("merchant ownership projection is inconsistent");
    }
    if (row.getMerchantStatus() == null || !MERCHANT_STATUSES.contains(row.getMerchantStatus())
        || row.getStoreStatus() == null || !STORE_STATUSES.contains(row.getStoreStatus())) {
      unavailable("merchant ownership projection is inconsistent");
    }
  }

  private static long ownerUserId(QueryContext context) {
    if (context == null || context.operatorType() == null
        || context.operatorId() == null || context.operatorId().isBlank()) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
    }
    if (context.operatorType() != OperatorType.USER) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "merchant admission requires a miniapp user");
    }
    try {
      return IDS.fromApi(context.operatorId());
    } catch (IllegalArgumentException invalidPrincipal) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
    }
  }

  private static long targetId(String value, String field) {
    if (value == null) invalid(field + " is required");
    try {
      return IDS.fromApi(value);
    } catch (IllegalArgumentException invalidTarget) {
      invalid(field + " is invalid");
      return 0;
    }
  }

  private static long positive(Long value, String field) {
    if (value == null || value <= 0) unavailable(field + " is invalid");
    return value;
  }

  private static String text(String value, int max, String field) {
    if (value == null || value.isEmpty() || value.length() > max
        || value.codePoints().anyMatch(Character::isISOControl)) {
      unavailable(field + " is invalid");
    }
    return value;
  }

  private static void invalid(String message) {
    throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
  }

  private static void notFound() {
    throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant admission resource not found");
  }

  private static void unavailable(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }
}
