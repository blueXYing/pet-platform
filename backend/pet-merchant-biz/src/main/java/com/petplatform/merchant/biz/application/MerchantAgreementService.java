package com.petplatform.merchant.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.command.MerchantAgreementConsentCommand;
import com.petplatform.merchant.api.dto.MerchantAgreementConsentDTO;
import com.petplatform.merchant.api.dto.MerchantAgreementDTO;
import com.petplatform.merchant.api.query.MerchantAgreementQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantOwnerEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAgreementMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-only immutable electronic-agreement read and first-consent workflow. */
public final class MerchantAgreementService {
    private static final String COMMAND = "merchant.agreement.consent";
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> APPLICATION_STATUSES =
            Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
    private static final Set<String> MERCHANT_STATUSES =
            Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    // APPLYING -> ACTIVE belongs to the still-unfrozen application review workflow.
    // Do not acknowledge a first signature while silently leaving activation undefined.
    private static final Set<String> FIRST_CONSENT_STATUSES = Set.of("ACTIVE");
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record ConsentResult(MerchantAgreementConsentDTO receipt, boolean created) {}

    private record ValidatedConsent(
            MerchantAgreementConsentCommand command,
            long merchantId,
            long ownerUserId,
            String traceId,
            byte[] requestKey,
            MerchantCanonicalParams.Canonical canonical
    ) {}

    private final MerchantAgreementStore store;
    private final ApplicationReviewFactsReader applicationFacts;
    private final Clock clock;

    public MerchantAgreementService(
            MerchantAgreementStore store,
            ApplicationReviewFactsReader applicationFacts,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.applicationFacts = Objects.requireNonNull(applicationFacts, "applicationFacts is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public MerchantAgreementDTO getAgreement(MerchantAgreementQuery query) {
        if (query == null) invalid("query is required");
        long merchantId = targetId(query.merchantId(), "merchantId");
        long ownerUserId = ownerUserId(query.context());
        return store.read(mapper -> {
            requireOwned(mapper, merchantId, ownerUserId, false);
            List<MerchantAgreementDocumentEntity> accepted = acceptedAgreements(mapper, merchantId, false);
            if (!accepted.isEmpty()) {
                MerchantAgreementDocumentEntity document = accepted.getFirst();
                validateDocument(document, true);
                OffsetDateTime acceptedAt = acceptedAt(document);
                return new MerchantAgreementDTO(
                        IDS.toApi(merchantId), document.getAgreementVersion(), document.getContent(),
                        document.getContentSha256(), "SIGNED", document.getAgreementVersion(), acceptedAt);
            }
            MerchantAgreementDocumentEntity current = mapper.selectCurrentAgreement();
            if (current == null) unavailable("current merchant agreement is unavailable");
            validateDocument(current, false);
            return new MerchantAgreementDTO(
                    IDS.toApi(merchantId), current.getAgreementVersion(), current.getContent(),
                    current.getContentSha256(), "NOT_SIGNED", null, null);
        });
    }

    public ConsentResult consent(MerchantAgreementConsentCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "agreement consent admission must not run inside an existing transaction");
        }
        ValidatedConsent validated = validateConsent(command);
        // Authorization precedes admission so an unauthorized caller cannot discover bindings.
        store.read(mapper -> {
            requireOwned(mapper, validated.merchantId(), validated.ownerUserId(), false);
            return null;
        });
        admitWithRecovery(validated);
        try {
            return executeConsent(validated);
        } catch (MerchantAgreementStore.CommitUnknown firstUnknown) {
            try {
                // Authoritative retry: SUCCEEDED replays, RESERVED executes; no new key is made.
                return executeConsent(validated);
            } catch (MerchantAgreementStore.CommitUnknown secondUnknown) {
                unavailable("agreement commit result remains unknown");
                return null;
            }
        }
    }

    private void admitWithRecovery(ValidatedConsent validated) {
        try {
            MerchantAgreementStore.Binding binding = store.admit(
                    validated.requestKey(), validated.canonical(), validated.traceId());
            MerchantAgreementStore.requireSameParams(binding, validated.canonical());
        } catch (MerchantAgreementStore.CommitUnknown firstUnknown) {
            try {
                MerchantAgreementStore.Binding recovered = store.admit(
                        validated.requestKey(), validated.canonical(), validated.traceId());
                MerchantAgreementStore.requireSameParams(recovered, validated.canonical());
            } catch (MerchantAgreementStore.CommitUnknown secondUnknown) {
                unavailable("idempotency admission result remains unknown");
            }
        }
    }

    private ConsentResult executeConsent(ValidatedConsent validated) {
        return store.execute(mapper -> {
            MerchantAgreementStore.Binding binding =
                    MerchantAgreementStore.requireBinding(mapper, validated.requestKey());
            MerchantAgreementStore.requireSameParams(binding, validated.canonical());
            String merchantStatus = requireOwned(
                    mapper, validated.merchantId(), validated.ownerUserId(), true);
            if ("SUCCEEDED".equals(binding.status())) {
                return new ConsentResult(readReceipt(binding.receiptJson(), validated.merchantId(),
                        validated.command().agreementVersion()), false);
            }

            List<MerchantAgreementDocumentEntity> accepted =
                    acceptedAgreements(mapper, validated.merchantId(), true);
            if (!accepted.isEmpty()) {
                MerchantAgreementDocumentEntity original = accepted.getFirst();
                validateDocument(original, true);
                // 松耦合（2026-11-20）：已签约商家重复确认一律幂等返回原签署回执——
                // 运营发布新版协议后，客户端提示值与历史版本不一致也不再 409。
                MerchantAgreementConsentDTO receipt = receipt(
                        validated.merchantId(), original.getAgreementVersion(), acceptedAt(original));
                succeed(mapper, validated.requestKey(), receipt);
                return new ConsentResult(receipt, false);
            }

            if (!FIRST_CONSENT_STATUSES.contains(merchantStatus)) {
                unavailable("first consent is not implemented for the current merchant status");
            }

            MerchantAgreementDocumentEntity current = mapper.selectCurrentAgreementForUpdate();
            if (current == null) unavailable("current merchant agreement is unavailable");
            validateDocument(current, false);
            // 松耦合（2026-11-20）：首签以行锁取到的当前版本盖章；客户端版本提示不参与
            // 强校验（运营在浏览与签署之间发布新版不再造成 409）。
            requireApprovedApplication(validated.merchantId());

            OffsetDateTime acceptedAt = OffsetDateTime.ofInstant(
                    clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            MerchantAgreementConsentDTO receipt = receipt(
                    validated.merchantId(), current.getAgreementVersion(), acceptedAt);
            String receiptJson = writeReceipt(receipt);
            int inserted = mapper.insertAcceptance(
                    store.nextId(), validated.merchantId(), positive(current.getVersionId(), "agreement version id"),
                    validated.ownerUserId(), acceptedAt.toLocalDateTime(), current.getContentSha256());
            if (inserted != 1) unavailable("agreement acceptance insert failed");
            markSucceeded(mapper, validated.requestKey(), receiptJson);
            return new ConsentResult(receipt, true);
        });
    }

    // ===== 2026-11-20 用户裁决：运营端协议管理（纯文本协议、发布即生效、松耦合版本链） =====

    /** 发布结果（版本号由服务端按时间生成，形如 v1732…，符合 VERSION 约束）。 */
    public record PublishedAgreement(long versionId, String agreementVersion, String contentSha256) {}

    public PublishedAgreement publish(String content, long operatorId) {
        if (content == null || content.isBlank()) invalid("协议内容不能为空");
        if (content.length() > 100_000) invalid("协议内容不能超过 100000 字");
        String sha = sha256(content);
        String version = "v" + clock.instant().toEpochMilli();
        java.time.LocalDateTime now =
                java.time.LocalDateTime.ofInstant(
                        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                        java.time.ZoneOffset.UTC);
        return store.execute(mapper -> {
            long id = store.nextId();
            if (mapper.insertVersion(id, version, content, sha, operatorId, now) != 1)
                unavailable("agreement version insert failed");
            // ON DUPLICATE KEY UPDATE：插入=1、更新=2 都算成功。
            if (mapper.upsertCurrentPointer(id, now) < 1)
                unavailable("agreement current pointer update failed");
            return new PublishedAgreement(id, version, sha);
        });
    }

    /**
     * 运营端版本历史的一行（应用层投影）。持久化实体不出本模块：HTTP 适配层只消费这个记录
     * （ARCH-002 跨模块持久化 / ARCH-004 控制器边界）。publishedAt 恒为 UTC 时刻。
     */
    public record AgreementVersionView(
            long versionId,
            String agreementVersion,
            String content,
            String contentSha256,
            OffsetDateTime publishedAt,
            Long publishedByOperatorId,
            boolean current
    ) {}

    /** 版本历史与“当前生效”标记在同一次只读事务内取齐，避免发布切换时读到错位的一对。 */
    public List<AgreementVersionView> listVersionsForAdmin() {
        return store.read(mapper -> {
            List<MerchantAgreementDocumentEntity> versions = mapper.selectAllVersions();
            MerchantAgreementDocumentEntity current = mapper.selectCurrentAgreement();
            long currentVersionId = current == null ? 0L : positive(current.getVersionId(), "agreement version id");
            List<AgreementVersionView> views = new ArrayList<>(versions.size());
            for (MerchantAgreementDocumentEntity version : versions) {
                LocalDateTime publishedAt = version.getPublishedAt();
                views.add(new AgreementVersionView(
                        positive(version.getVersionId(), "agreement version id"),
                        version.getAgreementVersion(),
                        version.getContent(),
                        version.getContentSha256(),
                        publishedAt == null ? null : publishedAt.atOffset(ZoneOffset.UTC),
                        version.getPublishedByOperatorId(),
                        version.getVersionId() == currentVersionId));
            }
            return List.copyOf(views);
        });
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void requireApprovedApplication(long merchantId) {
        ApplicationReviewFactsReader.Facts facts;
        try {
            facts = applicationFacts.read(merchantId);
        } catch (RuntimeException failure) {
            unavailable("application review facts are unavailable");
            return;
        }
        if (facts == null || facts.applicationStatus() == null
                || !APPLICATION_STATUSES.contains(facts.applicationStatus())) {
            unavailable("application review facts are missing or unknown");
        }
        if (!"APPROVED".equals(facts.applicationStatus())) {
            conflict("merchant application is not approved");
        }
    }

    static List<MerchantAgreementDocumentEntity> acceptedAgreements(
            MerchantAgreementMapper mapper,
            long merchantId,
            boolean forUpdate
    ) {
        int rawCount = mapper.countAcceptances(merchantId);
        List<MerchantAgreementDocumentEntity> joined = forUpdate
                ? mapper.selectAcceptedAgreementsForUpdate(merchantId)
                : mapper.selectAcceptedAgreements(merchantId);
        if (rawCount < 0 || rawCount > 1 || joined == null || joined.size() != rawCount) {
            unavailable("agreement acceptance facts are damaged");
        }
        return List.copyOf(joined);
    }

    private static String requireOwned(
            MerchantAgreementMapper mapper,
            long merchantId,
            long ownerUserId,
            boolean forUpdate
    ) {
        MerchantOwnerEntity found = forUpdate
                ? mapper.selectOwnedMerchantForUpdate(merchantId, ownerUserId)
                : mapper.selectOwnedMerchant(merchantId, ownerUserId);
        if (found == null) notFound();
        if (found.getId() == null || found.getId() != merchantId
                || found.getStatus() == null || !MERCHANT_STATUSES.contains(found.getStatus())) {
            unavailable("merchant ownership projection is inconsistent");
        }
        return found.getStatus();
    }

    static void validateDocument(MerchantAgreementDocumentEntity document, boolean accepted) {
        positive(document.getVersionId(), "agreement version id");
        if (document.getAgreementVersion() == null
                || !VERSION.matcher(document.getAgreementVersion()).matches()) {
            unavailable("agreement version is invalid");
        }
        if (document.getContent() == null) unavailable("agreement content is missing");
        byte[] content = document.getContent().getBytes(StandardCharsets.UTF_8);
        if (content.length < 1 || content.length > 65_535) unavailable("agreement content is invalid");
        if (document.getContentSha256() == null
                || !SHA256.matcher(document.getContentSha256()).matches()
                || !MerchantCanonicalParams.sha256Hex(content).equals(document.getContentSha256())) {
            unavailable("agreement content integrity check failed");
        }
        if (accepted) {
            if (document.getAcceptedContentSha256() == null
                    || !document.getContentSha256().equals(document.getAcceptedContentSha256())
                    || document.getAcceptedAt() == null) {
                unavailable("agreement acceptance integrity check failed");
            }
            acceptedAt(document);
        }
    }

    private static OffsetDateTime acceptedAt(MerchantAgreementDocumentEntity document) {
        if (document.getAcceptedAt() == null || document.getAcceptedAt().getNano() % 1_000_000 != 0) {
            unavailable("agreement acceptance time is invalid");
        }
        return document.getAcceptedAt().atOffset(ZoneOffset.UTC);
    }

    private static ValidatedConsent validateConsent(MerchantAgreementConsentCommand command) {
        if (command == null) invalid("command is required");
        long merchantId = targetId(command.merchantId(), "merchantId");
        // 2026-11-20 用户裁决：版本链松耦合——版本号与哈希降级为可选的客户端提示值，
        // 服务端以签署那一刻的当前版本为准盖章，不再因版本漂移拒绝签约。
        if (command.agreementVersion() != null
            && !VERSION.matcher(command.agreementVersion()).matches()) {
            invalid("agreementVersion is invalid");
        }
        if (command.contentSha256() != null
            && !SHA256.matcher(command.contentSha256()).matches()) {
            invalid("contentSha256 is invalid");
        }
        if (!Boolean.TRUE.equals(command.accepted())) invalid("accepted must be true");
        CommandContext context = commandContext(command.context());
        long ownerUserId = principalId(context.operatorId());
        String traceId = traceId(context.traceId());
        Map<String, Object> protectedFields = new LinkedHashMap<>();
        protectedFields.put("accepted", true);
        protectedFields.put("agreementVersion", command.agreementVersion());
        protectedFields.put("command", COMMAND);
        protectedFields.put("contentSha256", command.contentSha256());
        protectedFields.put("merchantId", command.merchantId());
        MerchantCanonicalParams.Canonical canonical = MerchantCanonicalParams.of(protectedFields);
        byte[] requestKey = MerchantRequestKey.encode(COMMAND, "USER", context.operatorId(),
                "MERCHANT:" + command.merchantId(), context.requestId());
        return new ValidatedConsent(command, merchantId, ownerUserId, traceId, requestKey, canonical);
    }

    private static CommandContext commandContext(CommandContext context) {
        if (context == null || context.operatorType() == null
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
        }
        if (context.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "agreement consent requires the merchant owner user");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException invalidContext) {
            invalid("requestId is invalid");
        }
        principalId(context.operatorId());
        return context;
    }

    private static long ownerUserId(QueryContext context) {
        if (context == null || context.operatorType() == null
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
        }
        if (context.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "agreement details require the merchant owner user");
        }
        return principalId(context.operatorId());
    }

    private static long principalId(String value) {
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalidPrincipal) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
        }
    }

    private static long targetId(String value, String field) {
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalidTarget) {
            invalid(field + " is invalid");
            return 0;
        }
    }

    private static String traceId(String value) {
        if (value == null) return null;
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)
                || value.getBytes(StandardCharsets.UTF_8).length > 128 || hasUnpairedSurrogate(value)) {
            invalid("traceId is invalid");
        }
        return value;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static MerchantAgreementConsentDTO receipt(
            long merchantId,
            String agreementVersion,
            OffsetDateTime acceptedAt
    ) {
        return new MerchantAgreementConsentDTO(
                IDS.toApi(merchantId), agreementVersion, acceptedAt, "SIGNED");
    }

    private static void succeed(
            MerchantAgreementMapper mapper,
            byte[] requestKey,
            MerchantAgreementConsentDTO receipt
    ) {
        markSucceeded(mapper, requestKey, writeReceipt(receipt));
    }

    private static void markSucceeded(MerchantAgreementMapper mapper, byte[] requestKey, String receiptJson) {
        if (mapper.markBindingSucceeded(requestKey, receiptJson) != 1) {
            unavailable("idempotency receipt completion failed");
        }
    }

    private static String writeReceipt(MerchantAgreementConsentDTO receipt) {
        try {
            return JSON.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            unavailable("agreement receipt serialization failed");
            return null;
        }
    }

    private static MerchantAgreementConsentDTO readReceipt(
            String json,
            long expectedMerchantId,
            String expectedAgreementVersion
    ) {
        try {
            MerchantAgreementConsentDTO receipt = JSON.readValue(json, MerchantAgreementConsentDTO.class);
            if (receipt == null
                    || !IDS.toApi(expectedMerchantId).equals(receipt.merchantId())
                    || receipt.agreementVersion() == null || !VERSION.matcher(receipt.agreementVersion()).matches()
                    || !expectedAgreementVersion.equals(receipt.agreementVersion())
                    || receipt.acceptedAt() == null || receipt.acceptedAt().getNano() % 1_000_000 != 0
                    || !ZoneOffset.UTC.equals(receipt.acceptedAt().getOffset())
                    || !"SIGNED".equals(receipt.signingStatus())) {
                unavailable("stored agreement receipt is invalid");
            }
            return receipt;
        } catch (JsonProcessingException failure) {
            unavailable("stored agreement receipt is unreadable");
            return null;
        }
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) unavailable(field + " is invalid");
        return value;
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant agreement resource not found");
    }

    private static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
