package com.petplatform.merchant.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.*;
import com.petplatform.merchant.api.query.*;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffReadEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStaffMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The five approved owner-only profile operations. A staff phone never grants a login identity. */
public final class MerchantStaffService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> MERCHANT_STATUSES = Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    private static final Set<String> EMPLOYMENT = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> APPLICATION = Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Intent(String action, long merchantId, long storeId, long staffId, long actorId,
                          String staffName, String phone, String employmentStatus, Boolean enabled,
                          Long expectedVersion, String requestId, String traceId, byte[] key,
                          MerchantCanonicalParams.Canonical canonical) {}

    private final MerchantStaffStore store;
    private final ApplicationReviewFactsReader applicationFacts;
    private final ApplicationValidationPorts.ProtectedValuePort protection;
    private final Clock clock;

    public MerchantStaffService(MerchantStaffStore store, ApplicationReviewFactsReader applicationFacts,
                                ApplicationValidationPorts.ProtectedValuePort protection, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.applicationFacts = Objects.requireNonNull(applicationFacts);
        this.protection = Objects.requireNonNull(protection);
        this.clock = Objects.requireNonNull(clock);
    }

    public MerchantStaffPageDTO listStaff(MerchantStaffListQuery query) {
        if (query == null) invalid();
        long merchantId = target(query.merchantId());
        long storeId = target(query.storeId());
        long owner = principal(query.context());
        if (query.page() < 1 || query.page() > 10_000 || query.pageSize() < 1 || query.pageSize() > 100
                || (query.employmentStatus() != null && !EMPLOYMENT.contains(query.employmentStatus()))) invalid();
        return store.read((mapper, agreements) -> {
            requireScope(mapper.selectOwnedScope(merchantId, storeId, owner), merchantId, storeId, owner);
            List<MerchantStaffDTO> filtered = new ArrayList<>();
            for (MerchantStaffReadEntity row : mapper.listAllStaff(storeId)) {
                MerchantStaffDTO dto = project(row, merchantId, storeId);
                if ((query.employmentStatus() == null || query.employmentStatus().equals(dto.employmentStatus()))
                        && (query.serviceEnabled() == null || query.serviceEnabled() == dto.serviceEnabled())) {
                    filtered.add(dto);
                }
            }
            int from = (int) Math.min(filtered.size(), (query.page() - 1L) * query.pageSize());
            int to = Math.min(filtered.size(), from + query.pageSize());
            return new MerchantStaffPageDTO(filtered.subList(from, to), query.page(), query.pageSize(), filtered.size());
        });
    }

    public MerchantStaffDTO getStaff(MerchantStaffQuery query) {
        if (query == null) invalid();
        long merchantId = target(query.merchantId());
        long storeId = target(query.storeId());
        long staffId = target(query.staffId());
        long owner = principal(query.context());
        return store.read((mapper, agreements) -> {
            requireScope(mapper.selectOwnedScope(merchantId, storeId, owner), merchantId, storeId, owner);
            MerchantStaffReadEntity row = mapper.selectStaff(staffId);
            if (row == null || !Objects.equals(row.getStoreId(), storeId)) notFound();
            if (!Objects.equals(row.getMerchantId(), merchantId))
                unavailable("staff merchant ownership is damaged");
            return project(row, merchantId, storeId);
        });
    }

    public MerchantStaffCommandResult createStaff(CreateMerchantStaffCommand command) {
        if (command == null) invalid();
        if (command.serviceEnabled() == null || command.employmentStatus() == null
                || !EMPLOYMENT.contains(command.employmentStatus())
                || ("INACTIVE".equals(command.employmentStatus()) && command.serviceEnabled())) invalid();
        Intent intent = intent("merchant.staff.create", command.merchantId(), command.storeId(), null,
                command.staffName(), command.phone(), command.employmentStatus(), command.serviceEnabled(),
                null, command.context());
        return write(intent);
    }

    public MerchantStaffCommandResult updateStaff(UpdateMerchantStaffCommand command) {
        if (command == null) invalid();
        return write(intent("merchant.staff.update", command.merchantId(), command.storeId(), command.staffId(),
                command.staffName(), command.phone(), null, null, command.expectedVersion(), command.context()));
    }

    public MerchantStaffCommandResult enableStaff(EnableMerchantStaffCommand command) {
        if (command == null) invalid();
        return write(intent("merchant.staff.enable", command.merchantId(), command.storeId(), command.staffId(),
                null, null, null, null, command.expectedVersion(), command.context()));
    }

    private MerchantStaffCommandResult write(Intent intent) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("staff command admission requires no outer transaction");
        // Admission is allowed only after current ownership and operating facts have been checked.
        store.read((mapper, agreements) -> {
            authorizeWrite(mapper, agreements, intent, false);
            return null;
        });
        try {
            MerchantAgreementStore.requireSameParams(store.admit(intent.key(), intent.canonical(), intent.traceId()),
                    intent.canonical());
        } catch (MerchantAgreementStore.CommitUnknown unknown) {
            MerchantAgreementStore.requireSameParams(store.admit(intent.key(), intent.canonical(), intent.traceId()),
                    intent.canonical());
        }
        try {
            return execute(intent);
        } catch (MerchantAgreementStore.CommitUnknown unknown) {
            try { return execute(intent); }
            catch (MerchantAgreementStore.CommitUnknown stillUnknown) {
                unavailable("staff command commit result is unknown");
                return null;
            }
        }
    }

    private MerchantStaffCommandResult execute(Intent intent) {
        return store.execute((mapper, agreements) -> {
            MerchantAgreementStore.Binding binding = MerchantAgreementStore.requireBinding(agreements, intent.key());
            MerchantAgreementStore.requireSameParams(binding, intent.canonical());
            authorizeWrite(mapper, agreements, intent, true);
            if ("SUCCEEDED".equals(binding.status())) {
                MerchantStaffDTO first = readReceipt(binding.receiptJson());
                if (!first.merchantId().equals(Long.toString(intent.merchantId()))
                        || !first.storeId().equals(Long.toString(intent.storeId()))
                        || (intent.staffId() != 0 && !first.staffId().equals(Long.toString(intent.staffId()))))
                    unavailable("staff receipt scope is damaged");
                // Current result ownership is checked again; the old receipt remains a historical projection.
                MerchantStaffReadEntity current = mapper.selectStaff(target(first.staffId()));
                if (current == null || !Objects.equals(current.getStoreId(), intent.storeId())) notFound();
                if (!Objects.equals(current.getMerchantId(), intent.merchantId()))
                    unavailable("staff merchant ownership is damaged");
                project(current, intent.merchantId(), intent.storeId());
                return new MerchantStaffCommandResult(first, false, true);
            }

            LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            long staffId = intent.staffId() == 0 ? store.nextId() : intent.staffId();
            MerchantStaffReadEntity before = null;
            int changed;
            if (intent.action().equals("merchant.staff.create")) {
                changed = mapper.insertStaff(staffId, intent.merchantId(), intent.storeId(), intent.staffName(),
                        intent.phone(), intent.employmentStatus(), intent.enabled() ? 1 : 0, now);
            } else {
                before = mapper.lockStaff(staffId);
                if (before == null || !Objects.equals(before.getStoreId(), intent.storeId())) notFound();
                if (!Objects.equals(before.getMerchantId(), intent.merchantId()))
                    unavailable("staff merchant ownership is damaged");
                project(before, intent.merchantId(), intent.storeId());
                if (!Objects.equals(before.getStaffVersion(), intent.expectedVersion())) conflict("staff version changed");
                if (before.getStaffVersion() == Long.MAX_VALUE) conflict("staff version exhausted");
                if (intent.action().equals("merchant.staff.enable")) {
                    if (!"ACTIVE".equals(before.getEmploymentStatus())) conflict("inactive staff cannot be enabled");
                    changed = mapper.enableStaff(staffId, intent.merchantId(), intent.storeId(),
                            intent.expectedVersion(), now);
                } else {
                    changed = mapper.updateStaff(staffId, intent.merchantId(), intent.storeId(),
                            intent.staffName(), intent.phone(), intent.expectedVersion(), now);
                }
            }
            if (changed != 1) conflict("staff write lost a concurrent version race");
            MerchantStaffReadEntity after = mapper.selectStaff(staffId);
            MerchantStaffDTO receipt = project(after, intent.merchantId(), intent.storeId());
            if (mapper.insertAudit(store.nextId(), intent.actorId(), intent.action(), intent.merchantId(),
                    intent.storeId(), staffId, intent.key(), intent.requestId().getBytes(StandardCharsets.UTF_8),
                    intent.traceId(), before == null ? null : before.getEmploymentStatus(),
                    after.getEmploymentStatus(), before == null ? null : before.getServiceEnabled(),
                    after.getServiceEnabled(), before == null ? null : before.getStaffVersion(),
                    after.getStaffVersion(), now) != 1) unavailable("staff audit insert failed");
            if (mapper.markBindingSucceeded(intent.key(), receiptJson(receipt)) != 1)
                unavailable("staff idempotency result update failed");
            return new MerchantStaffCommandResult(receipt, before == null, false);
        });
    }

    private void authorizeWrite(MerchantStaffMapper mapper, MerchantAgreementMapper agreements,
                                Intent intent, boolean lock) {
        MerchantStaffScopeEntity scope = lock
                ? mapper.lockOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId())
                : mapper.selectOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId());
        requireScope(scope, intent.merchantId(), intent.storeId(), intent.actorId());
        if (!"ACTIVE".equals(scope.getMerchantStatus()) || !"ACTIVE".equals(scope.getStoreStatus()))
            conflict("merchant or store is not operating");
        if (lock && mapper.lockApplication(intent.merchantId()) == null)
            unavailable("application review facts are missing");
        ApplicationReviewFactsReader.Facts application;
        try { application = applicationFacts.read(intent.merchantId()); }
        catch (RuntimeException failure) { unavailable("application review facts unavailable"); return; }
        if (application == null || !APPLICATION.contains(application.applicationStatus()))
            unavailable("application review facts are unknown");
        if (!"APPROVED".equals(application.applicationStatus())) conflict("merchant application is not approved");
        List<com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity> accepted =
                MerchantAgreementService.acceptedAgreements(agreements, intent.merchantId(), lock);
        if (accepted.isEmpty()) conflict("merchant agreement is not signed");
        MerchantAgreementService.validateDocument(accepted.getFirst(), true);
    }

    private static MerchantStaffScopeEntity requireScope(MerchantStaffScopeEntity scope,
            long merchantId, long storeId, long ownerId) {
        if (scope == null) notFound();
        if (!Objects.equals(scope.getMerchantId(), merchantId) || !Objects.equals(scope.getStoreId(), storeId)
                || !Objects.equals(scope.getOwnerUserId(), ownerId)
                || !MERCHANT_STATUSES.contains(scope.getMerchantStatus())
                || !STORE_STATUSES.contains(scope.getStoreStatus())) unavailable("merchant ownership facts are damaged");
        return scope;
    }

    private static MerchantStaffDTO project(MerchantStaffReadEntity row, long merchantId, long storeId) {
        if (row == null || row.getStaffId() == null || row.getStaffId() <= 0
                || !Objects.equals(row.getMerchantId(), merchantId) || !Objects.equals(row.getStoreId(), storeId)
                || row.getStaffName() == null || row.getStaffName().isBlank()
                || row.getStaffName().codePointCount(0, row.getStaffName().length()) > 64
                || !EMPLOYMENT.contains(row.getEmploymentStatus())
                || row.getServiceEnabled() == null || (row.getServiceEnabled() != 0 && row.getServiceEnabled() != 1)
                || ("INACTIVE".equals(row.getEmploymentStatus()) && row.getServiceEnabled() == 1)
                || row.getStaffVersion() == null || row.getStaffVersion() < 0
                || (row.getPhone() != null && !row.getPhone().matches("1[0-9]{10}")))
            unavailable("staff facts are damaged");
        String masked = row.getPhone() == null ? null
                : row.getPhone().substring(0, 3) + "****" + row.getPhone().substring(7);
        return new MerchantStaffDTO(Long.toString(merchantId), Long.toString(storeId),
                Long.toString(row.getStaffId()), row.getStaffName(), masked,
                row.getEmploymentStatus(), row.getServiceEnabled() == 1,
                Long.toString(row.getStaffVersion()));
    }

    private Intent intent(String action, String merchant, String store, String staff,
                                 String name, String phone, String employment, Boolean enabled,
                                 String expected, CommandContext context) {
        long merchantId = target(merchant), storeId = target(store);
        long staffId = staff == null ? 0 : target(staff);
        if (context == null || context.operatorType() == null || context.operatorId() == null)
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only merchant owner may manage staff");
        long actor = actorId(context.operatorId());
        try { PublicContractChecks.requireCommandRequestId(context); }
        catch (IllegalArgumentException bad) { invalid(); }
        if (action.equals("merchant.staff.create") || action.equals("merchant.staff.update"))
            if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 64
                    || hasUnpairedSurrogate(name)) invalid();
        if (phone != null && !phone.matches("1[0-9]{10}")) invalid();
        if (!action.equals("merchant.staff.create") && expected == null) invalid();
        Long expectedVersion = expected == null ? null : version(expected);
        String trace = context.traceId();
        if (trace != null && (trace.isBlank() || trace.getBytes(StandardCharsets.UTF_8).length > 128
                || trace.codePoints().anyMatch(Character::isISOControl) || hasUnpairedSurrogate(trace))) invalid();
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("command", action);
        fields.put("merchantId", merchant);
        fields.put("storeId", store);
        if (staff != null) fields.put("staffId", staff);
        // Sensitive profile values participate in equality via the pinned protected-value HMAC.
        // SQL28 canonical bytes never contain staff name or phone in plaintext.
        if (name != null) fields.put("staffName", protectedToken("merchant.staff.name.v1", name));
        if (action.equals("merchant.staff.create") || action.equals("merchant.staff.update"))
            fields.put("phone", phone == null ? null : protectedToken("merchant.staff.phone.v1", phone));
        if (employment != null) fields.put("employmentStatus", employment);
        if (enabled != null) fields.put("serviceEnabled", enabled);
        if (expected != null) fields.put("expectedVersion", expected);
        MerchantCanonicalParams.Canonical canonical = MerchantCanonicalParams.of(fields);
        // One owner cannot reuse the same command key against a different target.
        byte[] key = MerchantRequestKey.encode(action, "USER", context.operatorId(),
                "OWNER:" + context.operatorId(), context.requestId());
        return new Intent(action, merchantId, storeId, staffId, actor, name, phone, employment,
                enabled, expectedVersion, context.requestId(), trace, key, canonical);
    }

    private String protectedToken(String purpose, String value) {
        byte[] token;
        try { token = protection.protect(purpose, value).equalityToken(); }
        catch (RuntimeException failure) { unavailable("staff canonical protection unavailable"); return null; }
        if (token == null || token.length != 32) {
            unavailable("staff canonical protection is invalid");
        }
        return HexFormat.of().formatHex(token);
    }

    private static MerchantStaffDTO readReceipt(String value) {
        try {
            MerchantStaffDTO receipt = JSON.readValue(value, MerchantStaffDTO.class);
            target(receipt.merchantId()); target(receipt.storeId()); target(receipt.staffId());
            version(receipt.version());
            if (receipt.phoneMasked() != null && !receipt.phoneMasked().matches("1[0-9]{2}\\*{4}[0-9]{4}"))
                unavailable("staff receipt is damaged");
            return receipt;
        } catch (Exception failure) {
            unavailable("staff receipt is damaged");
            return null;
        }
    }

    private static String receiptJson(MerchantStaffDTO receipt) {
        try { return JSON.writeValueAsString(receipt); }
        catch (Exception failure) { unavailable("staff receipt serialization failed"); return null; }
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return true;
            } else if (Character.isLowSurrogate(ch)) return true;
        }
        return false;
    }
    private static long principal(QueryContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null)
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only merchant owner may read staff");
        return actorId(context.operatorId());
    }
    private static long actorId(String value) {
        try { return IDS.fromApi(value); }
        catch (IllegalArgumentException bad) { throw new ApiException(CommonApiCodes.UNAUTHORIZED, "invalid authenticated user"); }
    }
    private static long target(String value) {
        try { return IDS.fromApi(value); }
        catch (IllegalArgumentException bad) { invalid(); return 0; }
    }
    private static long version(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,18})")) invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException bad) { invalid(); return 0; }
    }
    private static void invalid() { throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid staff request"); }
    private static void notFound() { throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant staff not found"); }
    private static void conflict(String message) { throw new ApiException(CommonApiCodes.CONFLICT, message); }
    private static void unavailable(String message) { throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message); }
}
