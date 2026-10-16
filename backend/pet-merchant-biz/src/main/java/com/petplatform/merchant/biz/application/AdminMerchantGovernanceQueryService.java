package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminApplicationFact;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminAuthorization;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantDetailQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantLedgerDetail;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminSigningFact;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffInvitationListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffInvitationRow;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffMemberListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffMemberRow;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffProfile;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Check;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.CollectionCheck;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Decision;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.MerchantGovernanceAuthorizationPort;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Phase;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Resource;
import com.petplatform.merchant.biz.infrastructure.persistence.AdminMerchantGovernanceStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminActionRefRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminInvitationRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminMemberRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminMerchantLedgerRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminStaffRow;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.AdminMerchantGovernanceEntities.AdminStoreRow;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Platform merchant governance read model for the single operator (contract 64, PRD
 * 6.2.2/6.2.6). Read-only: the authorization port re-proves session/action/scope inside this
 * kernel before any row is read (ledger list via the collection entry check, single merchant
 * and merchant-scoped collections via the row scope). Unknown and foreign merchant ids read
 * as one COMMON_NOT_FOUND (anti-enumeration). Vocabularies are the storage ones; phones are
 * masked here so no plaintext phone ever travels with a projection. No write path; dataScope
 * ALL is the only supported mode (no uniform city attribution across the ledger rows —
 * CCR-PERM-001 §3).
 */
public final class AdminMerchantGovernanceQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_RANGE_DAYS = 366;
    private static final Set<String> MERCHANT_STATUSES =
            Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    private static final Set<String> EMPLOYMENT_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> MEMBER_STATUSES = Set.of("ENABLED", "DISABLED", "REVOKED");
    private static final Set<String> INVITATION_STATUSES = Set.of("INVITED", "CONFIRMED", "CANCELED");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final AdminMerchantGovernanceStore store;
    private final MerchantGovernanceAuthorizationPort authorization;

    public AdminMerchantGovernanceQueryService(
            AdminMerchantGovernanceStore store,
            MerchantGovernanceAuthorizationPort authorization) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
    }

    public PageResult<AdminMerchantLedgerSummary> listMerchants(AdminMerchantListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context, "merchant.read", "MERCHANT_LEDGER_READ");
        String status = vocabulary(query.status(), MERCHANT_STATUSES, "status");
        String storeStatus = vocabulary(query.storeStatus(), STORE_STATUSES, "storeStatus");
        text(query.cityCode(), 64, "cityCode");
        text(query.merchantTypeCode(), 32, "merchantTypeCode");
        String keyword = keyword(query.keyword());
        LocalDateTime from = utc(query.createdFrom(), "createdFrom");
        LocalDateTime to = utc(query.createdTo(), "createdTo");
        requirePairedRange(from, to);
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countLedger(
                status, storeStatus, query.cityCode(), query.merchantTypeCode(), keyword, from, to));
        List<AdminMerchantLedgerSummary> items = total == 0
                ? List.of()
                : store.read(mapper -> mapper.selectLedgerPage(
                        status, storeStatus, query.cityCode(), query.merchantTypeCode(), keyword,
                        from, to, pageSize, (long) (page - 1) * pageSize)).stream()
                        .map(AdminMerchantGovernanceQueryService::ledgerSummary)
                        .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public AdminMerchantLedgerDetail getMerchant(AdminMerchantDetailQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long merchantId = parse(query.merchantId(), "merchantId");
        AdminMerchantLedgerRow row = store.read(mapper -> mapper.selectLedgerRow(merchantId));
        if (row == null) notFound();
        authorizeMerchant(auth, context, "merchant.read", "MERCHANT_LEDGER_READ", row);
        List<AdminStoreRow> storeRows = store.read(mapper -> mapper.selectStores(merchantId));
        AdminMerchantLedgerSummary summary = ledgerSummary(row);
        return new AdminMerchantLedgerDetail(
                summary.merchantId(), summary.merchantName(), summary.status(),
                summary.merchantTypeCode(), summary.cityCode(), summary.storeCount(),
                summary.activeStoreCount(), summary.applicationStatus(), summary.signingStatus(),
                summary.providerMerchantNoMasked(), summary.createdAt(),
                storeRows.stream()
                        .map(store -> new com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantStore(
                                IDS.toApi(store.getId()), store.getStoreName(),
                                store.getAddress(), store.getStatus()))
                        .toList(),
                applicationFact(row), signingFact(row));
    }

    public PageResult<AdminStaffProfile> listStaff(AdminStaffListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long merchantId = requireMerchant(auth, context, "merchant.staff.read", "MERCHANT_STAFF_READ",
                query.merchantId());
        String employmentStatus =
                vocabulary(query.employmentStatus(), EMPLOYMENT_STATUSES, "employmentStatus");
        Long storeId = optionalId(query.storeId(), "storeId");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countStaff(
                merchantId, storeId, employmentStatus, query.serviceEnabled()));
        List<AdminStaffProfile> items = total == 0
                ? List.of()
                : store.read(mapper -> mapper.selectStaffPage(
                        merchantId, storeId, employmentStatus, query.serviceEnabled(),
                        pageSize, (long) (page - 1) * pageSize)).stream()
                        .map(AdminMerchantGovernanceQueryService::staffProfile)
                        .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public PageResult<AdminStaffMemberRow> listStaffMembers(AdminStaffMemberListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long merchantId = requireMerchant(auth, context, "merchant.staff.read", "MERCHANT_STAFF_READ",
                query.merchantId());
        String status = vocabulary(query.status(), MEMBER_STATUSES, "status");
        Long storeId = optionalId(query.storeId(), "storeId");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countMembers(merchantId, storeId, status));
        if (total == 0) {
            return new PageResult<>(List.of(), total, page, pageSize);
        }
        List<AdminMemberRow> rows = store.read(mapper -> mapper.selectMemberPage(
                merchantId, storeId, status, pageSize, (long) (page - 1) * pageSize));
        Map<Long, List<String>> actions = actionsByMember(rows.stream().map(AdminMemberRow::getMemberId).toList());
        List<AdminStaffMemberRow> items = rows.stream()
                .map(row -> memberRow(row, actions.getOrDefault(row.getMemberId(), List.of())))
                .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public PageResult<AdminStaffInvitationRow> listInvitations(AdminStaffInvitationListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        long merchantId = requireMerchant(auth, context, "merchant.staff.read", "MERCHANT_STAFF_READ",
                query.merchantId());
        String status = vocabulary(query.status(), INVITATION_STATUSES, "status");
        Long storeId = optionalId(query.storeId(), "storeId");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countInvitations(merchantId, storeId, status));
        if (total == 0) {
            return new PageResult<>(List.of(), total, page, pageSize);
        }
        List<AdminInvitationRow> rows = store.read(mapper -> mapper.selectInvitationPage(
                merchantId, storeId, status, pageSize, (long) (page - 1) * pageSize));
        Map<Long, List<String>> actions = new HashMap<>();
        List<Long> invitationIds = rows.stream().map(AdminInvitationRow::getId).toList();
        if (!invitationIds.isEmpty()) {
            for (AdminActionRefRow ref : store.read(mapper -> mapper.selectInvitationActions(invitationIds))) {
                actions.computeIfAbsent(ref.getOwnerId(), ignored -> new ArrayList<>()).add(ref.getActionCode());
            }
        }
        List<AdminStaffInvitationRow> items = rows.stream()
                .map(row -> new AdminStaffInvitationRow(
                        IDS.toApi(row.getId()), IDS.toApi(row.getStoreId()), row.getMemberName(),
                        mask(row.getPhone()), row.getStatus(),
                        List.copyOf(actions.getOrDefault(row.getId(), List.of())),
                        IDS.toApi(row.getInvitedBy()), instant(row.getCreatedAt()),
                        instant(row.getUpdatedAt())))
                .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    // ------------------------------------------------------------------ authorization

    private void authorizeCollection(AdminAuthorization auth, QueryContext context, String action, String purpose) {
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), action,
                purpose, Phase.READ_RESULT));
        requireDecision(decision, false);
    }

    private void authorizeMerchant(
            AdminAuthorization auth, QueryContext context, String action, String purpose,
            AdminMerchantLedgerRow row) {
        Decision decision = authorization.check(new Check(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), action,
                new Resource("MERCHANT", IDS.toApi(row.getId()), IDS.toApi(row.getId()),
                        row.getCityCode(),
                        Long.toString(row.getVersion() == null ? 0L : row.getVersion())),
                purpose, Phase.READ_RESULT));
        requireDecision(decision, true);
    }

    /** Merchant-scoped collection entry: the merchant row is the resource (anti-enum 404). */
    private long requireMerchant(
            AdminAuthorization auth, QueryContext context, String action, String purpose,
            String merchantIdValue) {
        long merchantId = parse(merchantIdValue, "merchantId");
        AdminMerchantLedgerRow row = store.read(mapper -> mapper.selectLedgerRow(merchantId));
        if (row == null) notFound();
        authorizeMerchant(auth, context, action, purpose, row);
        return merchantId;
    }

    private static void requireDecision(Decision decision, boolean resourceRead) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) {
            if (resourceRead && "RESOURCE_SCOPE_DENIED".equals(decision.reasonCode())) notFound();
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
        }
    }

    // ------------------------------------------------------------------ projections

    private static AdminMerchantLedgerSummary ledgerSummary(AdminMerchantLedgerRow row) {
        AdminSigningFact signing = signingFact(row);
        return new AdminMerchantLedgerSummary(
                IDS.toApi(row.getId()), row.getMerchantName(), row.getStatus(),
                row.getMerchantTypeCode(), row.getCityCode(),
                row.getStoreCount(), row.getActiveStoreCount(), row.getApplicationStatus(),
                signing == null ? null : signing.signingStatus(),
                maskProviderNo(row.getProviderMerchantNo()), instant(row.getCreatedAt()));
    }

    private static AdminApplicationFact applicationFact(AdminMerchantLedgerRow row) {
        if (row.getApplicationId() == null) return null;
        return new AdminApplicationFact(
                IDS.toApi(row.getApplicationId()), row.getApplicationNo(),
                row.getApplicationStatus(), instant(row.getApplicationSubmittedAt()),
                instant(row.getApplicationReviewedAt()));
    }

    private static AdminSigningFact signingFact(AdminMerchantLedgerRow row) {
        if (row.getSigningAcceptedAt() == null) {
            if (row.getAgreementVersion() == null) return null;
            return new AdminSigningFact("NOT_SIGNED", row.getAgreementVersion(), null);
        }
        return new AdminSigningFact(
                "SIGNED", row.getAgreementVersion(), instant(row.getSigningAcceptedAt()));
    }

    private static AdminStaffProfile staffProfile(AdminStaffRow row) {
        return new AdminStaffProfile(
                IDS.toApi(row.getId()), IDS.toApi(row.getStoreId()), row.getStaffName(),
                mask(row.getPhone()), row.getEmploymentStatus(),
                row.getServiceEnabled() != null && row.getServiceEnabled(),
                versionOf(row.getVersion()),
                instant(row.getCreatedAt()), instant(row.getUpdatedAt()));
    }

    private static AdminStaffMemberRow memberRow(AdminMemberRow row, List<String> actions) {
        boolean grantRevoked = "REVOKED".equals(row.getGrantStatus());
        return new AdminStaffMemberRow(
                IDS.toApi(row.getMemberId()),
                row.getStoreId() == null ? null : IDS.toApi(row.getStoreId()),
                row.getMemberStatus(), row.getMemberName(), mask(row.getPhone()),
                grantRevoked ? List.of() : List.copyOf(actions), grantRevoked,
                versionOf(row.getMemberVersion()),
                row.getGrantVersion() == null ? null : versionOf(row.getGrantVersion()),
                instant(row.getCreatedAt()), instant(row.getUpdatedAt()));
    }

    private Map<Long, List<String>> actionsByMember(List<Long> memberIds) {
        if (memberIds.isEmpty()) return Map.of();
        Map<Long, List<String>> actions = new TreeMap<>();
        for (AdminActionRefRow ref : store.read(mapper -> mapper.selectMemberActions(memberIds))) {
            actions.computeIfAbsent(ref.getOwnerId(), ignored -> new ArrayList<>()).add(ref.getActionCode());
        }
        return actions;
    }

    // ------------------------------------------------------------------ validation helpers

    private static QueryContext operator(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "平台运营身份必需");
        }
        return context;
    }

    private static AdminAuthorization reference(AdminAuthorization authorization) {
        if (authorization == null || authorization.sessionId() == null
                || authorization.sessionId().isBlank() || authorization.sessionGeneration() < 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
        }
        return authorization;
    }

    private static String vocabulary(String value, Set<String> allowed, String field) {
        if (value == null) return null;
        if (!allowed.contains(value)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
        return value;
    }

    private static void text(String value, int maxLength, String field) {
        if (value == null) return;
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxLength) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static String keyword(String value) {
        if (value == null) return null;
        int length = value.codePointCount(0, value.length());
        if (length < 2 || length > 64 || value.isBlank()
                || value.indexOf('%') >= 0 || value.indexOf('_') >= 0 || value.indexOf('\\') >= 0) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "keyword is invalid");
        }
        return "%" + value + "%";
    }

    private static void requirePairedRange(LocalDateTime from, LocalDateTime to) {
        if ((from == null) != (to == null)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "created range must be paired");
        }
        if (from != null && (to.isBefore(from) || Duration.between(from, to).toDays() > MAX_RANGE_DAYS)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "created range is invalid");
        }
    }

    private static long parse(String value, String field) {
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static Long optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static int page(int page) {
        return page < 1 ? 1 : page;
    }

    private static int pageSize(int pageSize) {
        return pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static LocalDateTime utc(OffsetDateTime value, String field) {
        if (value == null) return null;
        try {
            return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static OffsetDateTime instant(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /** 35/54号 pre-masked form; non-11-digit facts degrade to a full mask, never plaintext. */
    private static String mask(String phone) {
        if (phone == null) return null;
        return phone.length() == 11
                ? phone.substring(0, 3) + "****" + phone.substring(7)
                : "****";
    }

    /** PRD 6.2.2 子商户号脱敏展示：最多露出后 4 位。 */
    private static String maskProviderNo(String value) {
        if (value == null) return null;
        return value.length() >= 8 ? "****" + value.substring(value.length() - 4) : "****";
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "商家不存在");
    }

    /** CAS/row versions start at 0 (35/54号 wire) — not Snowflake ids, never PublicIdCodec. */
    private static String versionOf(Long version) {
        return Long.toUnsignedString(version == null ? 0L : version);
    }
}
