package com.petplatform.merchant.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.merchant.api.command.UpdateStoreProfileCommand;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi.StoreProfileQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStoreProfileStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreProfileRowEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreProfileRowEntity.Scope;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantStoreProfileMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Store profile read + full-replace update (contract 27 §6.4 supplement, PRD 5.7 店铺基础信息;
 * 2026-10-19 CCR expansion). Read: real OWNER scope, existing-material family (OFFLINE/FROZEN
 * stay readable). Write: the staff-write gate (OWNER + merchant/store ACTIVE + APPROVED
 * application + signed agreement) over the supplement-23 binding with the optimistic version
 * lock; the audit keeps changed field NAMES only, never values. The 2026-10-19 expansion adds
 * the merchant-maintained store facts (storeName/address/phone — the registered minimal
 * ruling: these changes do NOT trigger operations re-review; city/location/qualification stay
 * audit-owned), the STORE_PHOTO avatar asset, and the business status switch (OPEN/RESTING —
 * RESTING projects onto operatingStatus for the M face; platform OFFLINE/FROZEN still win).
 */
public final class MerchantStoreProfileService {
    static final Set<String> MERCHANT_STATUSES = Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    static final Set<String> APPLICATION = Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
    static final Set<String> PHOTO_MEDIA_TYPES = Set.of("image/jpeg", "image/png");
    static final Set<String> BUSINESS_STATUSES = Set.of("OPEN", "RESTING");
    static final int MAX_PHOTOS = 6;
    static final int MAX_STORE_NAME_CODE_POINTS = 128;
    static final int MAX_ADDRESS_CODE_POINTS = 255;
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<MerchantStoreProfileDTO.BusinessHour> HOUR_ORDER =
            Comparator.comparing(h -> h.days().getFirst());

    private record Intent(long merchantId, long storeId, long actorId, String storeName,
                          String address, String phone, String intro, String announcement,
                          List<String> photoAssetIds, List<MerchantStoreProfileDTO.BusinessHour> businessHours,
                          Integer promoLevel, String promoTitle, String promoText,
                          Long avatarAssetId, String businessStatus, long expectedVersion,
                          String requestId, String traceId, byte[] key,
                          MerchantCanonicalParams.Canonical canonical,
                          String storedBusinessHours, String storedPhotoAssetIds) {}

    public record OwnedAsset(long assetId, String mediaType, String status) {}

    /** Store-photo ownership facts (31号 pipeline, STORE_PHOTO purpose); failures are 503 upstream. */
    public interface PhotoAssetPort {
        List<OwnedAsset> resolveOwned(long ownerUserId, List<Long> assetIds);
    }

    private final MerchantStoreProfileStore store;
    private final ApplicationReviewFactsReader applicationFacts;
    private final PhotoAssetPort photos;
    private final Clock clock;

    public MerchantStoreProfileService(MerchantStoreProfileStore store,
                                       ApplicationReviewFactsReader applicationFacts,
                                       PhotoAssetPort photos, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.applicationFacts = Objects.requireNonNull(applicationFacts);
        this.photos = photos; // null reads as "no photo facts configured" → photo writes fail closed
        this.clock = Objects.requireNonNull(clock);
    }

    public MerchantStoreProfileDTO getStoreProfile(StoreProfileQuery query) {
        if (query == null) invalid();
        long merchantId = target(query.merchantId());
        long storeId = target(query.storeId());
        long owner = principal(query.context());
        return store.read((mapper, agreements) -> {
            Scope scope = mapper.selectOwnedScope(merchantId, storeId, owner);
            requireScope(scope, merchantId, storeId, owner);
            return project(scope, mapper.selectProfile(storeId));
        });
    }

    public MerchantStoreProfileDTO updateStoreProfile(UpdateStoreProfileCommand command) {
        if (command == null) invalid();
        Intent intent = intent(command);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("store profile command admission requires no outer transaction");
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
            try {
                return execute(intent);
            } catch (MerchantAgreementStore.CommitUnknown stillUnknown) {
                unavailable("store profile command commit result is unknown");
                return null;
            }
        }
    }

    private MerchantStoreProfileDTO execute(Intent intent) {
        return store.execute((mapper, agreements) -> {
            MerchantAgreementStore.Binding binding = MerchantAgreementStore.requireBinding(agreements, intent.key());
            MerchantAgreementStore.requireSameParams(binding, intent.canonical());
            Scope locked = authorizeWrite(mapper, agreements, intent, true);
            if ("SUCCEEDED".equals(binding.status())) {
                MerchantStoreProfileDTO first = readReceipt(binding.receiptJson());
                if (!first.merchantId().equals(Long.toString(intent.merchantId()))
                        || !first.storeId().equals(Long.toString(intent.storeId())))
                    unavailable("store profile receipt scope is damaged");
                MerchantStoreProfileRowEntity current = mapper.selectProfile(intent.storeId());
                if (current == null) unavailable("store profile receipt row is missing");
                return project(locked, current);
            }
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            MerchantStoreProfileRowEntity before = mapper.lockProfile(intent.storeId());
            String changed;
            int changedCount;
            if (before == null) {
                changedCount = mapper.insertProfile(intent.storeId(), intent.merchantId(), intent.intro(),
                        intent.announcement(), intent.storedBusinessHours(), intent.promoLevel(),
                        intent.promoTitle(), intent.promoText(), intent.storedPhotoAssetIds(),
                        intent.avatarAssetId(), intent.businessStatus(), now);
                changed = "created";
            } else {
                if (!Objects.equals(before.getMerchantId(), intent.merchantId()))
                    unavailable("store profile merchant ownership is damaged");
                if (before.getVersion() == null || before.getVersion() != intent.expectedVersion())
                    conflict("store profile version changed");
                if (before.getVersion() == Long.MAX_VALUE) conflict("store profile version exhausted");
                changedCount = mapper.updateProfile(intent.storeId(), intent.merchantId(), intent.intro(),
                        intent.announcement(), intent.storedBusinessHours(), intent.promoLevel(),
                        intent.promoTitle(), intent.promoText(), intent.storedPhotoAssetIds(),
                        intent.avatarAssetId(), intent.businessStatus(), intent.expectedVersion(), now);
                changed = changedFields(locked, before, intent);
            }
            if (changedCount != 1) conflict("store profile write lost a concurrent version race");
            // Merchant-maintained store facts ride the same command transaction (name/address/
            // phone only; qualification/city/location stay audit-owned). The facts row is
            // protected by the lockOwnedScope row lock taken in authorizeWrite.
            if (mapper.updateStoreFacts(intent.storeId(), intent.merchantId(), intent.storeName(),
                    intent.address(), intent.phone(), now) != 1)
                conflict("store facts write lost the concurrent scope race");
            MerchantStoreProfileRowEntity after = mapper.selectProfile(intent.storeId());
            Scope scope = mapper.selectOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId());
            requireScope(scope, intent.merchantId(), intent.storeId(), intent.actorId());
            MerchantStoreProfileDTO receipt = project(scope, after);
            Long fromVersion = before == null ? null : before.getVersion();
            if (mapper.insertAudit(store.nextId(), intent.actorId(), "merchant.store.profile.update",
                    intent.merchantId(), intent.storeId(), intent.key(),
                    intent.requestId().getBytes(StandardCharsets.UTF_8), intent.traceId(),
                    fromVersion, receipt.profile() == null ? 0 : Long.parseLong(receipt.profile().version()),
                    changed, now) != 1) unavailable("store profile audit insert failed");
            if (mapper.markBindingSucceeded(intent.key(), receiptJson(receipt)) != 1)
                unavailable("store profile idempotency result update failed");
            return receipt;
        });
    }

    private Scope authorizeWrite(MerchantStoreProfileMapper mapper, MerchantAgreementMapper agreements,
                                Intent intent, boolean lock) {
        Scope scope = lock
                ? mapper.lockOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId())
                : mapper.selectOwnedScope(intent.merchantId(), intent.storeId(), intent.actorId());
        requireScope(scope, intent.merchantId(), intent.storeId(), intent.actorId());
        if (!"ACTIVE".equals(scope.getMerchantStatus()) || !"ACTIVE".equals(scope.getStoreStatus()))
            conflict("merchant or store is not operating");
        // STORE_PHOTO assets must resolve to READY image assets owned by the acting account;
        // an unconfigured photo facts port keeps photo writes fail-closed (503).
        validatedPhotoIds(intent.actorId(), intent.photoAssetIds());
        validatedAvatarId(intent.actorId(), intent.avatarAssetId());
        if (lock && mapper.lockApplication(intent.merchantId()) == null)
            unavailable("application review facts are missing");
        ApplicationReviewFactsReader.Facts application;
        try { application = applicationFacts.read(intent.merchantId()); }
        catch (RuntimeException failure) { unavailable("application review facts unavailable"); return null; }
        if (application == null || !APPLICATION.contains(application.applicationStatus()))
            unavailable("application review facts are unknown");
        if (!"APPROVED".equals(application.applicationStatus())) conflict("merchant application is not approved");
        List<MerchantAgreementDocumentEntity> accepted =
                MerchantAgreementService.acceptedAgreements(agreements, intent.merchantId(), lock);
        if (accepted.isEmpty()) conflict("merchant agreement is not signed");
        MerchantAgreementService.validateDocument(accepted.getFirst(), true);
        return scope;
    }

    private Intent intent(UpdateStoreProfileCommand command) {
        long merchantId = target(command.merchantId());
        long storeId = target(command.storeId());
        CommandContext context = command.context();
        if (context == null || context.operatorType() == null || context.operatorId() == null)
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context required");
        if (context.operatorType() != OperatorType.USER)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only merchant owner may edit the store profile");
        long actor = actorId(context.operatorId());
        try { PublicContractChecks.requireCommandRequestId(context); }
        catch (IllegalArgumentException bad) { invalid(); }
        // 2026-10-19 expansion: merchant-maintained store facts. Name/address are mandatory in
        // the full-replace body; phone clears via the empty string. City/location/qualification
        // have no edit path at all.
        String storeName = requiredText(command.storeName(), MAX_STORE_NAME_CODE_POINTS, "店铺名称");
        String address = requiredText(command.address(), MAX_ADDRESS_CODE_POINTS, "门店地址");
        String phone = clearedPhone(command.phone());
        String intro = clearedText(command.intro(), 500);
        String announcement = clearedText(command.announcement(), 200);
        String promoTitle = clearedText(command.promoTitle(), 100);
        String promoText = clearedText(command.promoText(), 100);
        Integer promoLevel = command.promoLevel();
        if (promoLevel != null && (promoLevel < 1 || promoLevel > 9)) invalid();
        List<String> photoAssetIds = photoIds(command.photoAssetIds());
        List<MerchantStoreProfileDTO.BusinessHour> hours = businessHours(command.businessHours());
        String storedHours = hours == null ? null : json(hours);
        String storedPhotos = photoAssetIds == null ? null : json(photoAssetIds);
        Long avatarAssetId = avatarId(command.avatarAssetId());
        String businessStatus = businessStatus(command.businessStatus());
        if (command.expectedVersion() == null) invalid();
        long expectedVersion = version(command.expectedVersion());
        String trace = context.traceId();
        if (trace != null && (trace.isBlank() || trace.getBytes(StandardCharsets.UTF_8).length > 128
                || trace.codePoints().anyMatch(Character::isISOControl) || hasUnpairedSurrogate(trace))) invalid();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", "merchant.store.profile.update");
        fields.put("merchantId", command.merchantId());
        fields.put("storeId", command.storeId());
        fields.put("storeName", storeName);
        fields.put("address", address);
        fields.put("phone", phone);
        fields.put("intro", intro);
        fields.put("announcement", announcement);
        fields.put("photoAssetIds", photoAssetIds);
        fields.put("businessHours", hours);
        fields.put("promoLevel", promoLevel);
        fields.put("promoTitle", promoTitle);
        fields.put("promoText", promoText);
        fields.put("avatarAssetId", avatarAssetId == null ? null : avatarAssetId.toString());
        fields.put("businessStatus", businessStatus);
        fields.put("expectedVersion", command.expectedVersion());
        MerchantCanonicalParams.Canonical canonical = MerchantCanonicalParams.of(fields);
        byte[] key = MerchantRequestKey.encode("merchant.store.profile.update", "USER", context.operatorId(),
                "OWNER:" + context.operatorId(), context.requestId());
        return new Intent(merchantId, storeId, actor, storeName, address, phone, intro, announcement,
                photoAssetIds, hours, promoLevel, promoTitle, promoText, avatarAssetId,
                businessStatus, expectedVersion, context.requestId(), trace, key, canonical,
                storedHours, storedPhotos);
    }

    /** Resolves the photo ownership facts for an already-shaped id list; failures stay 503. */
    List<String> validatedPhotoIds(long ownerUserId, List<String> photoAssetIds) {
        if (photoAssetIds == null || photoAssetIds.isEmpty() || photos == null) {
            if (photoAssetIds != null && !photoAssetIds.isEmpty() && photos == null)
                unavailable("store photo facts are not configured");
            return photoAssetIds;
        }
        List<Long> ids = new ArrayList<>();
        for (String value : photoAssetIds) ids.add(Long.parseLong(value));
        List<OwnedAsset> resolved;
        try { resolved = photos.resolveOwned(ownerUserId, ids); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { unavailable("store photo facts are unavailable"); return null; }
        if (resolved == null || resolved.size() != ids.size()) invalid();
        Set<Long> seen = new HashSet<>();
        for (OwnedAsset asset : resolved) {
            if (asset == null || asset.assetId() <= 0 || !seen.add(asset.assetId())
                    || !"READY".equals(asset.status()) || !PHOTO_MEDIA_TYPES.contains(asset.mediaType()))
                invalid();
        }
        return photoAssetIds;
    }

    /** Same STORE_PHOTO facts family as the photo list, for the single avatar reference. */
    private void validatedAvatarId(long ownerUserId, Long avatarAssetId) {
        if (avatarAssetId == null) return;
        if (photos == null) unavailable("store photo facts are not configured");
        List<OwnedAsset> resolved;
        try { resolved = photos.resolveOwned(ownerUserId, List.of(avatarAssetId)); }
        catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { unavailable("store photo facts are unavailable"); return; }
        if (resolved == null || resolved.size() != 1) invalid();
        OwnedAsset asset = resolved.getFirst();
        if (asset == null || asset.assetId() != avatarAssetId.longValue()
                || !"READY".equals(asset.status()) || !PHOTO_MEDIA_TYPES.contains(asset.mediaType()))
            invalid();
    }

    private static String clearedText(String value, int maxCodePoints) {
        if (value == null) return null;
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > maxCodePoints || hasUnpairedSurrogate(value)) invalid();
        return value;
    }

    /** Mandatory full-replace text (store facts name/address): 1..max code points, not blank. */
    private static String requiredText(String value, int maxCodePoints, String label) {
        if (value == null || value.isBlank()
                || value.codePointCount(0, value.length()) > maxCodePoints
                || hasUnpairedSurrogate(value)) invalid();
        return value;
    }

    /** Optional store contact phone: digits only, 5..32 (mobile/landline); empty clears. */
    private static String clearedPhone(String value) {
        if (value == null) invalid();
        if (value.isEmpty()) return null;
        if (!value.matches("[0-9]{5,32}")) invalid();
        return value;
    }

    /** Single avatar asset reference; the empty string clears (stores NULL). */
    private static Long avatarId(String value) {
        if (value == null) invalid();
        if (value.isEmpty()) return null;
        if (!value.matches("[1-9][0-9]{0,18}")) invalid();
        return Long.parseLong(value);
    }

    /** OPEN/RESTING switch; the empty string clears back to the OPEN projection (NULL). */
    private static String businessStatus(String value) {
        if (value == null) invalid();
        if (value.isEmpty()) return null;
        if (!BUSINESS_STATUSES.contains(value)) invalid();
        return value;
    }

    private static List<String> photoIds(List<String> values) {
        if (values == null) return null;
        if (values.size() > MAX_PHOTOS) invalid();
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            if (value == null || !value.matches("[1-9][0-9]{0,18}") || !distinct.add(value)) invalid();
        }
        return List.copyOf(values);
    }

    private static List<MerchantStoreProfileDTO.BusinessHour> businessHours(
            List<MerchantStoreProfileDTO.BusinessHour> values) {
        if (values == null) return null;
        if (values.isEmpty()) return null; // [] clears the informational field
        if (values.size() > 7) invalid();
        Set<Integer> covered = new HashSet<>();
        for (MerchantStoreProfileDTO.BusinessHour hour : values) {
            if (hour == null || hour.days() == null || hour.days().isEmpty()
                    || hour.start() == null || hour.end() == null
                    || !hour.start().matches("([01][0-9]|2[0-3]):[0-5][0-9]")
                    || !hour.end().matches("([01][0-9]|2[0-3]):[0-5][0-9]")
                    || hour.end().compareTo(hour.start()) <= 0) invalid();
            List<Integer> days = hour.days();
            int previous = 0;
            for (Integer day : days) {
                if (day == null || day < 1 || day > 7 || day <= previous || !covered.add(day)) invalid();
                previous = day;
            }
        }
        List<MerchantStoreProfileDTO.BusinessHour> sorted = new ArrayList<>(values);
        sorted.sort(HOUR_ORDER);
        return List.copyOf(sorted);
    }

    private static String changedFields(Scope scope, MerchantStoreProfileRowEntity before, Intent intent) {
        List<String> names = new ArrayList<>();
        // 2026-10-19 expansion: the merchant-maintained store facts join the audit name list
        // (values stay out of the audit — the row and merchant_store carry current values).
        if (!Objects.equals(scope.getStoreName(), intent.storeName())) names.add("storeName");
        if (!Objects.equals(scope.getAddress(), intent.address())) names.add("address");
        if (!Objects.equals(scope.getPhone(), intent.phone())) names.add("phone");
        if (!Objects.equals(before.getIntro(), intent.intro())) names.add("intro");
        if (!Objects.equals(before.getAnnouncement(), intent.announcement())) names.add("announcement");
        if (!Objects.equals(before.getBusinessHours(), intent.storedBusinessHours())) names.add("businessHours");
        if (!Objects.equals(before.getPromoLevel(), intent.promoLevel())) names.add("promoLevel");
        if (!Objects.equals(before.getPromoTitle(), intent.promoTitle())) names.add("promoTitle");
        if (!Objects.equals(before.getPromoText(), intent.promoText())) names.add("promoText");
        if (!Objects.equals(before.getPhotoAssetIds(), intent.storedPhotoAssetIds())) names.add("photoAssetIds");
        if (!Objects.equals(before.getAvatarAssetId(), intent.avatarAssetId())) names.add("avatarAssetId");
        if (!Objects.equals(before.getBusinessStatus(), intent.businessStatus())) names.add("businessStatus");
        return String.join(",", names);
    }

    private MerchantStoreProfileDTO project(Scope scope, MerchantStoreProfileRowEntity row) {
        if (scope == null || scope.getStoreId() == null || scope.getMerchantId() == null
                || !MERCHANT_STATUSES.contains(scope.getMerchantStatus())
                || !STORE_STATUSES.contains(scope.getStoreStatus()))
            unavailable("store profile facts are damaged");
        MerchantStoreProfileDTO.Profile profile;
        if (row == null) {
            profile = new MerchantStoreProfileDTO.Profile(null, null, null, null, null, null, null,
                    null, "OPEN", "0");
        } else {
            if (!Objects.equals(row.getStoreId(), scope.getStoreId())
                    || !Objects.equals(row.getMerchantId(), scope.getMerchantId())
                    || row.getVersion() == null || row.getVersion() < 0)
                unavailable("store profile row is damaged");
            if (row.getBusinessStatus() != null && !BUSINESS_STATUSES.contains(row.getBusinessStatus()))
                unavailable("store profile business status is damaged");
            profile = new MerchantStoreProfileDTO.Profile(row.getIntro(), row.getAnnouncement(),
                    photoIdList(row.getPhotoAssetIds()), hourList(row.getBusinessHours()),
                    row.getPromoLevel(), row.getPromoTitle(), row.getPromoText(),
                    row.getAvatarAssetId() == null ? null : row.getAvatarAssetId().toString(),
                    row.getBusinessStatus() == null ? "OPEN" : row.getBusinessStatus(),
                    Long.toString(row.getVersion()));
        }
        // operatingStatus projection (2026-10-19 expansion): the merchant-set RESTING switch
        // shows only while the platform facts stay ACTIVE; OFFLINE/FROZEN still win.
        String operating;
        if (!"ACTIVE".equals(scope.getStoreStatus()) || !"ACTIVE".equals(scope.getMerchantStatus())) {
            operating = "FROZEN".equals(scope.getMerchantStatus()) || "FROZEN".equals(scope.getStoreStatus())
                    ? "FROZEN" : "OFFLINE";
        } else {
            operating = "RESTING".equals(profile.businessStatus()) ? "RESTING" : "OPERATING";
        }
        MerchantStoreProfileDTO.ReadonlyFacts facts = new MerchantStoreProfileDTO.ReadonlyFacts(
                scope.getStoreName(), scope.getAddress(), scope.getCityCode(), masked(scope.getPhone()),
                decimal(scope.getLongitude()), decimal(scope.getLatitude()),
                scope.getMerchantStatus(), scope.getStoreStatus(), operating, null);
        return new MerchantStoreProfileDTO(Long.toString(scope.getMerchantId()),
                Long.toString(scope.getStoreId()), facts, profile);
    }

    private List<String> photoIdList(String json) {
        if (json == null) return null;
        try { return List.copyOf(Arrays.asList(JSON.readValue(json, String[].class))); }
        catch (Exception failure) { unavailable("store profile photo facts are damaged"); return null; }
    }

    private List<MerchantStoreProfileDTO.BusinessHour> hourList(String json) {
        if (json == null) return null;
        try {
            return List.of(JSON.readValue(json, MerchantStoreProfileDTO.BusinessHour[].class));
        } catch (Exception failure) { unavailable("store profile business hours are damaged"); return null; }
    }

    /** The read projection leaves applicationStatus to the HTTP adapter's optional facts seam. */
    public MerchantStoreProfileDTO withApplicationStatus(MerchantStoreProfileDTO dto, String applicationStatus) {
        MerchantStoreProfileDTO.ReadonlyFacts facts = dto.readonlyFacts();
        return new MerchantStoreProfileDTO(dto.merchantId(), dto.storeId(),
                new MerchantStoreProfileDTO.ReadonlyFacts(facts.storeName(), facts.address(), facts.cityCode(),
                        facts.phoneMasked(), facts.longitude(), facts.latitude(), facts.merchantStatus(),
                        facts.storeStatus(), facts.operatingStatus(), applicationStatus),
                dto.profile());
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String masked(String phone) {
        return phone != null && phone.matches("1[0-9]{10}")
                ? phone.substring(0, 3) + "****" + phone.substring(7) : null;
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { unavailable("store profile parameters are not serializable"); return null; }
    }

    private static MerchantStoreProfileDTO readReceipt(String value) {
        try {
            MerchantStoreProfileDTO receipt = JSON.readValue(value, MerchantStoreProfileDTO.class);
            target0(receipt.merchantId());
            target0(receipt.storeId());
            if (receipt.profile() == null || receipt.profile().version() == null) unavailable("receipt is damaged");
            return receipt;
        } catch (ApiException known) { throw known; }
        catch (Exception failure) { unavailable("store profile receipt is damaged"); return null; }
    }

    private static void target0(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) unavailable("store profile receipt is damaged");
    }

    private static String receiptJson(MerchantStoreProfileDTO receipt) {
        try { return JSON.writeValueAsString(receipt); }
        catch (Exception failure) { unavailable("store profile receipt serialization failed"); return null; }
    }

    private static Scope requireScope(Scope scope, long merchantId, long storeId, long ownerId) {
        if (scope == null) notFound();
        if (!Objects.equals(scope.getMerchantId(), merchantId) || !Objects.equals(scope.getStoreId(), storeId)
                || !Objects.equals(scope.getOwnerUserId(), ownerId)
                || !MERCHANT_STATUSES.contains(scope.getMerchantStatus())
                || !STORE_STATUSES.contains(scope.getStoreStatus()))
            unavailable("merchant ownership facts are damaged");
        return scope;
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
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only merchant owner may read the store profile");
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

    private static void invalid() { throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "invalid store profile request"); }
    private static void notFound() { throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant store not found"); }
    private static void conflict(String message) { throw new ApiException(CommonApiCodes.CONFLICT, message); }
    private static void unavailable(String message) { throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message); }
}
