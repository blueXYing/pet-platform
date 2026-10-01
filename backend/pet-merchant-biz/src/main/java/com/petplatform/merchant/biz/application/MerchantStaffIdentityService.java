package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantStaffIdentityFactsDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMembershipDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantStaffActionQuery;
import com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery;
import com.petplatform.merchant.api.query.MerchantStaffMembershipQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffIdentityStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffIdentityScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffMembershipRowEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
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
import java.util.regex.Pattern;

/**
 * Read-side STAFF login-identity resolution (supplement 27 §5; HTTP10 admission §1-3; SSOT §30/§37).
 * Membership and action facts are re-read per call; disabled members are DENIED while revoked or
 * unrelated targets are anti-enumeration 404. No binding command, grant writer, HTTP surface or
 * verification mapping is delivered here: those follow the approved binding CCR. Relation storage
 * design comes from supplement-27 storage §2; phone, role name and service_enabled never grant
 * login or actions.
 */
public final class MerchantStaffIdentityService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Pattern ACTION_CODE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,99}");
    private static final Set<String> MERCHANT_STATUSES =
            Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    private static final Set<String> MEMBER_STATUSES = Set.of("ENABLED", "DISABLED", "REVOKED");
    private static final Set<String> GRANT_STATUSES = Set.of("ENABLED", "REVOKED");
    private static final Set<String> APPLICATION_STATUSES =
            Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");

    private final MerchantStaffIdentityStore store;
    private final ScheduleCapacityGuardApi guard;
    private final ApplicationReviewFactsReader applicationFacts;
    private final Clock clock;

    public MerchantStaffIdentityService(MerchantStaffIdentityStore store, ScheduleCapacityGuardApi guard,
            ApplicationReviewFactsReader applicationFacts, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.applicationFacts = Objects.requireNonNull(applicationFacts, "applicationFacts is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public MerchantStaffMembershipPageDTO listStaffMemberships(MerchantStaffMembershipQuery query) {
        if (query == null) invalid("query is required");
        if (query.page() < 1 || query.page() > 10_000) invalid("page is invalid");
        if (query.pageSize() < 1 || query.pageSize() > 50) invalid("pageSize is invalid");
        long userId = principal(query.context());
        return store.read(mapper -> {
            long total = mapper.countStaffMembershipRows(userId);
            List<MerchantStaffMembershipRowEntity> rows = mapper.listStaffMembershipRows(userId,
                    query.pageSize(), (int) ((query.page() - 1L) * query.pageSize()));
            List<MerchantStaffMembershipDTO> items = new ArrayList<>(rows.size());
            for (MerchantStaffMembershipRowEntity row : rows) items.add(project(row));
            return new MerchantStaffMembershipPageDTO(List.copyOf(items), query.page(), query.pageSize(), total);
        });
    }

    public MerchantStaffIdentityFactsDTO getStaffFacts(MerchantStaffIdentityFactsQuery query) {
        if (query == null) invalid("query is required");
        long merchantId = target(query.merchantId(), "merchantId");
        long storeId = target(query.storeId(), "storeId");
        long userId = principal(query.context());
        return store.read((mapper, agreements) -> {
            MerchantStaffIdentityScopeEntity scope = mapper.selectIdentityScope(merchantId, storeId, userId);
            if (scope == null) notFound();
            validateScope(scope, merchantId, storeId);
            requireActiveRelation(scope);
            ApplicationReviewFactsReader.Facts application = readApplicationFacts(merchantId);
            String signingStatus = readSigningStatus(agreements, merchantId);
            List<String> actions = codes(mapper.listActionCodes(scope.getMemberId(), storeId));
            return new MerchantStaffIdentityFactsDTO(query.merchantId(), query.storeId(), "STAFF", true,
                    application.applicationStatus(), signingStatus, scope.getMerchantStatus(),
                    scope.getStoreStatus(), authzVersion(scope, application.applicationStatus(), signingStatus),
                    checkedAt(), staffId(scope), actions);
        });
    }

    public void requireStaffAction(MerchantStaffActionQuery query) {
        if (query == null) invalid("query is required");
        long merchantId = target(query.merchantId(), "merchantId");
        long storeId = target(query.storeId(), "storeId");
        long userId = principal(query.context());
        if (query.actionCode() == null || !ACTION_CODE.matcher(query.actionCode()).matches())
            invalid("actionCode is invalid");
        // The caller owns the shared store-guard transaction; locks below join it.
        guard.requireHeld(query.storeId(), store.source());
        MerchantStaffIdentityScopeEntity scope = store.joining().lockIdentityScope(merchantId, storeId, userId);
        if (scope == null) notFound();
        validateScope(scope, merchantId, storeId);
        requireActiveRelation(scope);
        if ("FROZEN".equals(scope.getMerchantStatus()) || "FROZEN".equals(scope.getStoreStatus()))
            denied("frozen staff write actions are not approved");
        if ("OFFLINE".equals(scope.getMerchantStatus()) || "OFFLINE".equals(scope.getStoreStatus()))
            denied("offline staff action classification is pending approval");
        ApplicationReviewFactsReader.Facts application = readApplicationFacts(merchantId);
        if (!"APPROVED".equals(application.applicationStatus())) denied("merchant application is not approved");
        String signing = readSigningStatus(store.agreements(), merchantId);
        if (!"SIGNED".equals(signing)) denied("merchant agreement is not signed");
        List<String> actions = store.joining().listActionCodesLocked(scope.getMemberId(), storeId);
        if (!codes(actions).contains(query.actionCode())) denied("staff action is not granted");
    }

    private static void requireActiveRelation(MerchantStaffIdentityScopeEntity scope) {
        String memberStatus = scope.getMemberStatus();
        if (!MEMBER_STATUSES.contains(memberStatus)) unavailable("member status is unknown");
        if ("REVOKED".equals(memberStatus)) notFound();
        if ("DISABLED".equals(memberStatus)) denied("staff member is disabled");
        if (scope.getGrantId() == null) notFound();
        String grantStatus = scope.getGrantStatus();
        if (!GRANT_STATUSES.contains(grantStatus)) unavailable("store grant status is unknown");
        if ("REVOKED".equals(grantStatus)) notFound();
        if (scope.getGrantVersion() == null || scope.getGrantVersion() < 0)
            unavailable("store grant version is missing");
        if (scope.getGrantStaffId() != null
                && (!Objects.equals(scope.getGrantStaffStoreId(), scope.getStoreId())
                        || !Objects.equals(scope.getGrantStaffMerchantId(), scope.getMerchantId())))
            unavailable("staff grant reference is inconsistent");
    }

    private static void validateScope(MerchantStaffIdentityScopeEntity scope, long merchantId, long storeId) {
        if (scope.getMerchantId() == null || scope.getMerchantId() != merchantId
                || scope.getStoreId() == null || scope.getStoreId() != storeId
                || scope.getMemberId() == null || scope.getMemberId() <= 0
                || scope.getMerchantVersion() == null || scope.getMerchantVersion() < 0
                || scope.getStoreVersion() == null || scope.getStoreVersion() < 0
                || scope.getMemberVersion() == null || scope.getMemberVersion() < 0)
            unavailable("staff identity scope facts are damaged");
        if (!MERCHANT_STATUSES.contains(scope.getMerchantStatus())
                || !STORE_STATUSES.contains(scope.getStoreStatus()))
            unavailable("merchant or store status is unknown");
    }

    private static MerchantStaffMembershipDTO project(MerchantStaffMembershipRowEntity row) {
        if (row == null || row.getMerchantId() == null || row.getMerchantId() <= 0
                || row.getStoreId() == null || row.getStoreId() <= 0
                || row.getMemberId() == null || row.getMemberId() <= 0
                || row.getGrantId() == null || row.getGrantId() <= 0
                || row.getGrantVersion() == null || row.getGrantVersion() < 0
                || row.getMemberVersion() == null || row.getMemberVersion() < 0)
            unavailable("staff membership facts are damaged");
        if (!"ENABLED".equals(row.getMemberStatus()) || !"ENABLED".equals(row.getGrantStatus()))
            unavailable("staff membership facts are damaged");
        if (row.getMerchantName() == null || row.getMerchantName().isEmpty()
                || row.getMerchantName().length() > 128 || row.getStoreName() == null
                || row.getStoreName().isEmpty() || row.getStoreName().length() > 128)
            unavailable("staff membership naming is damaged");
        if (row.getGrantStaffId() != null
                && (!Objects.equals(row.getGrantStaffStoreId(), row.getStoreId())
                        || !Objects.equals(row.getGrantStaffMerchantId(), row.getMerchantId())))
            unavailable("staff grant reference is inconsistent");
        return new MerchantStaffMembershipDTO(Long.toString(row.getMerchantId()), row.getMerchantName(),
                Long.toString(row.getStoreId()), row.getStoreName(), "STAFF", staffId(row));
    }

    private ApplicationReviewFactsReader.Facts readApplicationFacts(long merchantId) {
        ApplicationReviewFactsReader.Facts facts;
        try {
            facts = applicationFacts.read(merchantId);
        } catch (RuntimeException failure) {
            unavailable("application review facts are unavailable");
            return null;
        }
        if (facts == null || facts.applicationStatus() == null
                || !APPLICATION_STATUSES.contains(facts.applicationStatus()))
            unavailable("application review facts are unknown");
        return facts;
    }

    private static String readSigningStatus(MerchantAgreementStore agreements, long merchantId) {
        List<MerchantAgreementDocumentEntity> accepted = agreements.joinCurrentTransaction(
                (MerchantAgreementMapper mapper) -> MerchantAgreementService.acceptedAgreements(mapper, merchantId, false));
        if (accepted.isEmpty()) return "NOT_SIGNED";
        MerchantAgreementService.validateDocument(accepted.getFirst(), true);
        return "SIGNED";
    }

    private static List<String> codes(List<String> values) {
        if (values == null) unavailable("granted actions are unavailable");
        List<String> codes = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || !ACTION_CODE.matcher(value).matches())
                unavailable("granted action code is damaged");
            codes.add(value);
        }
        return List.copyOf(codes);
    }

    private static String authzVersion(MerchantStaffIdentityScopeEntity scope,
            String applicationStatus, String signingStatus) {
        String material = "STAFF|" + scope.getMerchantStatus() + "|" + scope.getStoreStatus()
                + "|" + applicationStatus + "|" + signingStatus + "|" + scope.getMerchantVersion()
                + "|" + scope.getStoreVersion() + "|" + scope.getMemberVersion() + "|" + scope.getGrantVersion();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) hex.append(String.format("%02x", digest[index]));
            return hex.toString();
        } catch (Exception unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "authz version unavailable");
        }
    }

    private OffsetDateTime checkedAt() {
        return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static String staffId(MerchantStaffIdentityScopeEntity scope) {
        return scope.getGrantStaffId() == null ? null : Long.toString(scope.getGrantStaffId());
    }

    private static String staffId(MerchantStaffMembershipRowEntity row) {
        return row.getGrantStaffId() == null ? null : Long.toString(row.getGrantStaffId());
    }

    private static long principal(QueryContext context) {
        if (context == null || context.operatorType() == null
                || context.operatorId() == null || context.operatorId().isBlank())
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "staff identity requires a miniapp user");
        try {
            return IDS.fromApi(context.operatorId());
        } catch (IllegalArgumentException invalidPrincipal) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
        }
    }

    private static long target(String value, String field) {
        if (value == null) invalid(field + " is required");
        try {
            long parsed = IDS.fromApi(value);
            if (parsed <= 0) invalid(field + " is invalid");
            return parsed;
        } catch (IllegalArgumentException invalidTarget) {
            invalid(field + " is invalid");
            return 0;
        }
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant staff membership not found");
    }

    private static void denied(String message) {
        throw new ApiException(CommonApiCodes.FORBIDDEN, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
