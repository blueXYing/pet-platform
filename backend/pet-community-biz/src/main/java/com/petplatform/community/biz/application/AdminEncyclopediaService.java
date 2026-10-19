package com.petplatform.community.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.BreedMutation;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CreateBreedCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CreateCategoryCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.BreedStatusCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CategoryMutation;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CategoryStatusCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.UpdateBreedCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.UpdateCategoryCommand;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedCard;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedDetail;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedDetailQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedListQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminExportQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.EncyclopediaExport;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminCategoryListQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminCategoryRow;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Check;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.CollectionCheck;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Decision;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Phase;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Port;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Resource;
import com.petplatform.community.biz.infrastructure.persistence.EncyclopediaAdminStore;
import com.petplatform.community.biz.infrastructure.persistence.EncyclopediaAdminStore.Binding;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminEntities.AdminBreedEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaAdminEntities.AdminCategoryEntity;
import com.petplatform.community.biz.infrastructure.persistence.mapper.EncyclopediaAdminMapper;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Platform encyclopedia governance kernel (contract 65 §3, PRD 6.10, A-005): all-status reads
 * for the operator plus white-listed maintenance writes (category name/sort/enable/disable,
 * breed summary/cover/body/aliases/tags/sort, breed publish/offline — the 62 leftover). Every
 * entry re-proves the current session/action/scope through the injected authorization port
 * (controller snapshots are never evidence); writes bind requestId idempotency on
 * command_idempotency (14号) with same-parameter replay returning the first receipt and
 * cross-parameter replay answering 409 IDEMPOTENCY_KEY_CONFLICT, plus an expectedVersion
 * optimistic guard (0 updated rows → 409 COMMON_CONFLICT). v0.4 (2026-10-19 user ruling
 * "继续扩展CCR") opens creation and export with the same guard family:
 * createCategory mints a new dictionary code (lexical 2..16, born ENABLED), createBreed mints
 * an OFFLINE breed under an ENABLED category, and exportAll streams the bounded (1 MiB)
 * read-only CSV document — no deletion route exists and rich text stays non-writable
 * (CCR-ADM-A005-OPS-001 §4 as amended).
 */
public final class AdminEncyclopediaService
        implements AdminEncyclopediaQueryApi, AdminEncyclopediaCommandApi {

    static final int MAX_PAGE_SIZE = 100;
    static final int KEYWORD_LIMIT = 40;

    private static final Set<String> BREED_STATUSES = Set.of("PUBLISHED", "OFFLINE");
    /** v0.4 export byte ceiling; the kernel fails closed before any body byte is committed. */
    public static final int EXPORT_MAX_BYTES = 1024 * 1024;

    private final EncyclopediaAdminStore store;
    private final Port authorization;
    private final java.time.Clock clock;

    public AdminEncyclopediaService(EncyclopediaAdminStore store, Port authorization,
            java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    // ------------------------------------------------------------------- reads

    @Override
    public PageResult<AdminCategoryRow> listCategories(AdminCategoryListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        authorizeCollection(query.authorization(), context, "encyclopedia.read", "ENCYCLOPEDIA_READ");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        return store.read(m -> {
            long total = m.countCategories();
            List<AdminCategoryRow> items = total == 0 ? List.of()
                    : m.selectCategories(pageSize, (page - 1) * pageSize).stream()
                            .map(row -> categoryRow(m, row)).toList();
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    @Override
    public PageResult<AdminBreedCard> listBreeds(AdminBreedListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        authorizeCollection(query.authorization(), context, "encyclopedia.read", "ENCYCLOPEDIA_READ");
        String status = statusFilter(query.status());
        Long categoryId = categoryFilter(query.categoryCode());
        String keywordPattern = keywordPattern(query.keyword());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        return store.read(m -> {
            long total = m.countBreeds(status, categoryId, keywordPattern);
            List<AdminBreedCard> items = total == 0 ? List.of()
                    : m.selectBreeds(status, categoryId, keywordPattern, pageSize, (page - 1) * pageSize)
                            .stream().map(AdminEncyclopediaService::breedCard).toList();
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    @Override
    public AdminBreedDetail getBreed(AdminBreedDetailQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        long breedId = breedId(query.breedId());
        authorizeQueryRow(query.authorization(), context, "breed.read", "ENCYCLOPEDIA_BREED",
                String.valueOf(breedId));
        AdminBreedEntity row = store.read(m -> m.selectBreedById(breedId));
        if (row == null) throw notFound();
        return breedDetail(row);
    }

    // ------------------------------------------------------------------ writes

    @Override
    public CategoryMutation updateCategory(UpdateCategoryCommand command) {
        Objects.requireNonNull(command, "command is required");
        String code = requiredCode(command.categoryCode());
        String name = displayText(command.categoryName(), 2, 10, "categoryName");
        if (command.sortNo() < 0 || command.sortNo() > 999) invalid("sortNo is outside 0..999");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("categoryCode", code);
        params.put("categoryName", name);
        params.put("sortNo", command.sortNo());
        params.put("expectedVersion", command.expectedVersion());
        return runBound(params, command.context(), command.authorization(), "encyclopedia.category.update",
                "encyclopedia.manage", "ENCYCLOPEDIA_CATEGORY", code,
                key -> {
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.updateCategory(code, name, command.sortNo(), null,
                            null, command.expectedVersion(), now)) != 1) throw conflict();
                    AdminCategoryEntity row =
                            store.read(m -> m.selectCategoryByCode(code));
                    if (row == null) throw notFound();
                    return receipt(key, "categoryCode", row.getCategoryCode(), row.getCategoryName(),
                            row.getStatus(), row.getSortNo(), row.getVersion());
                },
                AdminEncyclopediaService::categoryMutationOf);
    }

    @Override
    public CategoryMutation enableCategory(CategoryStatusCommand command) {
        return categoryTransition(command, "ENABLED", "DISABLED", "encyclopedia.category.enable");
    }

    @Override
    public CategoryMutation disableCategory(CategoryStatusCommand command) {
        return categoryTransition(command, "DISABLED", "ENABLED", "encyclopedia.category.disable");
    }

    @Override
    public BreedMutation updateBreed(UpdateBreedCommand command) {
        Objects.requireNonNull(command, "command is required");
        long breedId = breedId(command.breedId());
        String summary = displayText(command.summary(), 1, 120, "summary");
        String coverUrl = optionalText(command.coverUrl(), 512, "coverUrl");
        String bodyType = optionalText(command.bodyType(), 32, "bodyType");
        List<String> aliases = strings(command.aliases(), 5, 50, "aliases");
        List<String> tags = strings(command.tags(), 10, 30, "tags");
        if (command.sortNo() < 0 || command.sortNo() > 9999) invalid("sortNo is outside 0..9999");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("breedId", breedId);
        params.put("summary", summary);
        params.put("coverUrl", coverUrl);
        params.put("bodyType", bodyType);
        params.put("aliases", aliases);
        params.put("tags", tags);
        params.put("sortNo", command.sortNo());
        params.put("expectedVersion", command.expectedVersion());
        return runBound(params, command.context(), command.authorization(),
                "encyclopedia.breed.update", "breed.manage", "ENCYCLOPEDIA_BREED",
                String.valueOf(breedId),
                key -> {
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.updateBreedFields(breedId, summary, coverUrl, bodyType,
                            encodeStrings(aliases), encodeStrings(tags), command.sortNo(),
                            command.expectedVersion(), now)) != 1) {
                        throw conflict();
                    }
                    AdminBreedEntity row = store.read(m -> m.selectBreedById(breedId));
                    if (row == null) throw notFound();
                    return receipt(key, "breedId", Long.toUnsignedString(row.getId()), row.getName(),
                            row.getStatus(), row.getSortNo(), row.getVersion());
                },
                AdminEncyclopediaService::breedMutationOf);
    }

    @Override
    public BreedMutation publishBreed(BreedStatusCommand command) {
        return breedTransition(command, "PUBLISHED", "OFFLINE", "encyclopedia.breed.publish");
    }

    @Override
    public BreedMutation offlineBreed(BreedStatusCommand command) {
        return breedTransition(command, "OFFLINE", "PUBLISHED", "encyclopedia.breed.offline");
    }

    @Override
    public CategoryMutation createCategory(CreateCategoryCommand command) {
        Objects.requireNonNull(command, "command is required");
        String code = categoryCodeLexeme(command.categoryCode());
        String name = displayText(command.categoryName(), 2, 10, "categoryName");
        if (command.sortNo() < 0 || command.sortNo() > 999) invalid("sortNo is outside 0..999");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("categoryCode", code);
        params.put("categoryName", name);
        params.put("sortNo", command.sortNo());
        return runBound(params, command.context(), command.authorization(),
                "encyclopedia.category.create", "encyclopedia.manage", "ENCYCLOPEDIA_CATEGORY", code,
                key -> {
                    LocalDateTime now = nowUtc();
                    try {
                        if (store.execute(m -> m.insertCategory(store.nextId(), code, name,
                                command.sortNo(), now)) != 1) {
                            throw conflict();
                        }
                    } catch (DuplicateKeyException duplicate) {
                        throw conflict();
                    }
                    AdminCategoryEntity row = store.read(m -> m.selectCategoryByCode(code));
                    if (row == null) throw notFound();
                    return receipt(key, "categoryCode", row.getCategoryCode(), row.getCategoryName(),
                            row.getStatus(), row.getSortNo(), row.getVersion());
                },
                AdminEncyclopediaService::categoryMutationOf);
    }

    @Override
    public BreedMutation createBreed(CreateBreedCommand command) {
        Objects.requireNonNull(command, "command is required");
        String code = categoryCodeLexeme(command.categoryCode());
        String name = displayText(command.name(), 1, 30, "name");
        String summary = displayText(command.summary(), 1, 120, "summary");
        String coverUrl = optionalText(command.coverUrl(), 512, "coverUrl");
        String bodyType = optionalText(command.bodyType(), 32, "bodyType");
        List<String> aliases = strings(command.aliases(), 5, 50, "aliases");
        List<String> tags = strings(command.tags(), 10, 30, "tags");
        if (command.sortNo() < 0 || command.sortNo() > 9999) invalid("sortNo is outside 0..9999");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("categoryCode", code);
        params.put("name", name);
        params.put("summary", summary);
        params.put("coverUrl", coverUrl);
        params.put("bodyType", bodyType);
        params.put("aliases", aliases);
        params.put("tags", tags);
        params.put("sortNo", command.sortNo());
        return runBound(params, command.context(), command.authorization(),
                "encyclopedia.breed.create", "breed.manage", "ENCYCLOPEDIA_BREED", name,
                key -> {
                    AdminCategoryEntity category = store.read(m -> m.selectCategoryByCode(code));
                    if (category == null) throw notFound();
                    if (!"ENABLED".equals(category.getStatus())) {
                        throw conflict();
                    }
                    long breedId = store.nextId();
                    try {
                        if (store.execute(m -> m.insertBreed(breedId, category.getId(), name,
                                encodeStrings(aliases), encodeStrings(tags), summary, coverUrl,
                                bodyType, command.sortNo(), nowUtc())) != 1) {
                            throw conflict();
                        }
                    } catch (DuplicateKeyException duplicate) {
                        throw conflict();
                    }
                    AdminBreedEntity row = store.read(m -> m.selectBreedById(breedId));
                    if (row == null) throw notFound();
                    return receipt(key, "breedId", Long.toUnsignedString(row.getId()), row.getName(),
                            row.getStatus(), row.getSortNo(), row.getVersion());
                },
                AdminEncyclopediaService::breedMutationOf);
    }

    @Override
    public EncyclopediaExport exportAll(AdminExportQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        authorizeCollection(query.authorization(), context, "encyclopedia.read", "ENCYCLOPEDIA_READ");
        List<EncyclopediaCsvDocuments.CategoryRow> categories = store.read(m ->
                m.selectAllCategoriesForExport().stream()
                        .map(row -> new EncyclopediaCsvDocuments.CategoryRow(
                                row.getCategoryCode(), row.getCategoryName(), row.getStatus(),
                                row.getSortNo() == null ? 0 : row.getSortNo(),
                                m.countBreedsByCategory(row.getId()),
                                m.countPublishedBreedsByCategory(row.getId()),
                                row.getVersion() == null ? 0 : row.getVersion(),
                                row.getCreatedAt(), row.getUpdatedAt()))
                        .toList());
        List<EncyclopediaCsvDocuments.BreedRow> breeds = store.read(m ->
                m.selectAllBreedsForExport().stream()
                        .map(row -> new EncyclopediaCsvDocuments.BreedRow(
                                row.getId() == null ? "" : Long.toUnsignedString(row.getId()),
                                row.getCategoryCode(), row.getName(), row.getAliases(),
                                row.getTags(), row.getSummary(), row.getCoverUrl(),
                                row.getBodyType(), row.getStatus(),
                                row.getSortNo() == null ? 0 : row.getSortNo(),
                                row.getVersion() == null ? 0 : row.getVersion(),
                                row.getCreatedAt(), row.getUpdatedAt()))
                        .toList());
        String generatedAt = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
                .format(clock.instant());
        byte[] csv = EncyclopediaCsvDocuments.generate(categories, breeds, generatedAt);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                .withZone(ZoneOffset.UTC).format(clock.instant());
        return new EncyclopediaExport("encyclopedia-export-" + stamp + "Z.csv", csv);
    }

    // ----------------------------------------------------------------- helpers

    private CategoryMutation categoryTransition(
            CategoryStatusCommand command, String toStatus, String fromStatus, String namespace) {
        Objects.requireNonNull(command, "command is required");
        String code = requiredCode(command.categoryCode());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("categoryCode", code);
        params.put("toStatus", toStatus);
        params.put("expectedVersion", command.expectedVersion());
        return runBound(params, command.context(), command.authorization(), namespace,
                "encyclopedia.manage", "ENCYCLOPEDIA_CATEGORY", code,
                key -> {
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.updateCategory(code, null, null, toStatus, fromStatus,
                            command.expectedVersion(), now)) != 1) {
                        throw conflict();
                    }
                    AdminCategoryEntity row = store.read(m -> m.selectCategoryByCode(code));
                    if (row == null) throw notFound();
                    return receipt(key, "categoryCode", row.getCategoryCode(), row.getCategoryName(),
                            row.getStatus(), row.getSortNo(), row.getVersion());
                },
                AdminEncyclopediaService::categoryMutationOf);
    }

    private BreedMutation breedTransition(
            BreedStatusCommand command, String toStatus, String fromStatus, String namespace) {
        Objects.requireNonNull(command, "command is required");
        long breedId = breedId(command.breedId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("breedId", breedId);
        params.put("toStatus", toStatus);
        params.put("expectedVersion", command.expectedVersion());
        return runBound(params, command.context(), command.authorization(), namespace,
                "encyclopedia.publish", "ENCYCLOPEDIA_BREED", String.valueOf(breedId),
                key -> {
                    LocalDateTime now = nowUtc();
                    if (store.execute(m -> m.updateBreedStatus(breedId, fromStatus, toStatus,
                            command.expectedVersion(), now)) != 1) {
                        throw conflict();
                    }
                    AdminBreedEntity row = store.read(m -> m.selectBreedById(breedId));
                    if (row == null) throw notFound();
                    return receipt(key, "breedId", Long.toUnsignedString(row.getId()), row.getName(),
                            row.getStatus(), row.getSortNo(), row.getVersion());
                },
                AdminEncyclopediaService::breedMutationOf);
    }

    /** Receipt written into the binding; replay re-renders the mutation from these facts. */
    private record Receipt(
            String id, String code, String name, String status, int sortNo, long version,
            OffsetDateTime occurredAt) {}

    /**
     * Shared write pipeline: authorize (row phase) → admit the idempotency binding (replay on
     * SUCCEEDED) → run the kernel work and mark the binding succeeded in one commit each.
     * Commit-unknown outcomes get exactly one recovery attempt, then fail closed 503.
     */
    private <T> T runBound(
            Map<String, Object> params, CommandContext rawContext,
            com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization auth,
            String namespace, String actionCode, String resourceType, String resourceId,
            java.util.function.Function<String, Receipt> work,
            java.util.function.BiFunction<Receipt, Boolean, T> render) {
        CommandContext context = commandContext(rawContext);
        authorizeRow(auth, operatorContext(context), actionCode, resourceType, resourceId);
        var canonical = CommunityAdminCanonicalParams.of(params);
        String key = storeKey(namespace, context, resourceId);
        Binding binding = admitWithRecovery(key, canonical);
        // 14号 equality fact: a stored binding answers only when the protected parameters are
        // byte-identical; any other payload under the same requestId is a 409 key conflict.
        if (binding.paramsSha256() == null
                || !binding.paramsSha256().equals(canonical.sha256())
                || !java.util.Arrays.equals(binding.paramsCanonical(), canonical.bytes())) {
            throw new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    "encyclopedia admin idempotency conflict");
        }
        Receipt receipt;
        if ("SUCCEEDED".equals(binding.status())) {
            receipt = parseReceipt(binding.receiptJson());
        } else {
            receipt = runWithRecovery(key, work);
        }
        return render.apply(receipt, !"SUCCEEDED".equals(binding.status()));
    }

    private Receipt runWithRecovery(String key, java.util.function.Function<String, Receipt> work) {
        try {
            return work.apply(key);
        } catch (EncyclopediaAdminStore.CommitUnknown first) {
            try {
                return work.apply(key);
            } catch (EncyclopediaAdminStore.CommitUnknown second) {
                throw unavailable("encyclopedia admin commit result remains unknown");
            }
        }
    }

    /** Persists the mutation receipt on the binding; called inside the execution transaction. */
    private Receipt receipt(String key, String idField, String id, String name, String status,
            Integer sortNo, Long version) {
        Receipt receipt = new Receipt(id, id, name, status,
                sortNo == null ? 0 : sortNo, version == null ? 0 : version, now());
        String json = "{\"" + idField + "\":" + jsonString(id) + ",\"name\":"
                + jsonString(name) + ",\"status\":\"" + status + "\",\"sortNo\":"
                + receipt.sortNo() + ",\"version\":" + receipt.version()
                + ",\"occurredAt\":\"" + receipt.occurredAt() + "\"}";
        if (store.execute(m -> m.markBindingSucceeded(key, json)) != 1) {
            throw unavailable("encyclopedia admin idempotency marking failed");
        }
        return receipt;
    }

    private static Receipt parseReceipt(String json) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String occurred = root.path("occurredAt").asText(null);
            return new Receipt(
                    root.path("breedId").asText(null),
                    root.path("categoryCode").asText(null),
                    root.path("name").asText(null),
                    root.path("status").asText(null),
                    root.path("sortNo").asInt(0),
                    root.path("version").asLong(0),
                    occurred == null ? null : OffsetDateTime.parse(occurred));
        } catch (Exception failure) {
            throw unavailable("stored encyclopedia admin receipt is not readable");
        }
    }

    private static CategoryMutation categoryMutationOf(Receipt receipt, boolean fresh) {
        return new CategoryMutation(receipt.code(), receipt.name(), receipt.status(),
                receipt.sortNo(), receipt.version(), receipt.occurredAt());
    }

    private static BreedMutation breedMutationOf(Receipt receipt, boolean fresh) {
        return new BreedMutation(receipt.id(), receipt.name(), receipt.status(),
                receipt.sortNo(), receipt.version(), receipt.occurredAt());
    }

    private Binding admitWithRecovery(String key, CommunityAdminCanonicalParams.Canonical canonical) {
        try {
            return store.admit(key, canonical);
        } catch (EncyclopediaAdminStore.CommitUnknown first) {
            try {
                return store.admit(key, canonical);
            } catch (EncyclopediaAdminStore.CommitUnknown second) {
                throw unavailable("encyclopedia admin admission result remains unknown");
            }
        }
    }

    private void authorizeCollection(com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminAuthorization auth,
            QueryContext context, String actionCode, String purpose) {
        require(auth);
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(),
                actionCode, purpose, Phase.READ_RESULT));
        requireDecision(decision);
    }

    private void authorizeRow(com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization auth,
            QueryContext context, String actionCode, String resourceType, String resourceId) {
        require(auth);
        check(auth.sessionId(), auth.sessionGeneration(), context, actionCode, resourceType, resourceId);
    }

    private void authorizeQueryRow(com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminAuthorization auth,
            QueryContext context, String actionCode, String resourceType, String resourceId) {
        require(auth);
        check(auth.sessionId(), auth.sessionGeneration(), context, actionCode, resourceType, resourceId);
    }

    private void check(String sessionId, long sessionGeneration, QueryContext context,
            String actionCode, String resourceType, String resourceId) {
        Decision decision = authorization.check(new Check(
                sessionId, sessionGeneration, context.operatorId(),
                actionCode, new Resource(resourceType, resourceId, null, null, null),
                actionCode.replace('.', '_').toUpperCase(Locale.ROOT), Phase.EXECUTE));
        requireDecision(decision);
    }

    private void requireDecision(Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    private static void require(com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
    }

    private static void require(com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
    }

    private QueryContext operator(QueryContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated query context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "encyclopedia admin belongs to operators");
        }
        return context;
    }

    private CommandContext commandContext(CommandContext context) {
        if (context == null || context.operatorType() == null || context.operatorId() == null
                || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated command context is required");
        }
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "encyclopedia admin belongs to operators");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException bad) {
            invalid("requestId is invalid");
        }
        return context;
    }

    private QueryContext operatorContext(CommandContext context) {
        return new QueryContext(context.traceId(), context.operatorType(), context.operatorId());
    }

    private String storeKey(String namespace, CommandContext context, String scope) {
        return "rk-v1:" + namespace + ":" + context.operatorType().name() + ":"
                + context.operatorId() + ":" + scope + ":" + context.requestId();
    }

    private static AdminCategoryRow categoryRow(EncyclopediaAdminMapper m, AdminCategoryEntity row) {
        return new AdminCategoryRow(
                row.getCategoryCode(), row.getCategoryName(), row.getStatus(),
                row.getSortNo() == null ? 0 : row.getSortNo(),
                m.countBreedsByCategory(row.getId()),
                m.countPublishedBreedsByCategory(row.getId()),
                row.getVersion() == null ? 0 : row.getVersion(),
                utc(row.getCreatedAt()), utc(row.getUpdatedAt()));
    }

    private static AdminBreedCard breedCard(AdminBreedEntity row) {
        return new AdminBreedCard(
                Long.toUnsignedString(row.getId()), row.getCategoryCode(), row.getCategoryName(),
                row.getName(), decodeStrings(row.getAliases()), decodeStrings(row.getTags()),
                row.getSummary(), row.getCoverUrl(), row.getStatus(),
                row.getSortNo() == null ? 0 : row.getSortNo(),
                row.getVersion() == null ? 0 : row.getVersion(), utc(row.getUpdatedAt()));
    }

    private static AdminBreedDetail breedDetail(AdminBreedEntity row) {
        return new AdminBreedDetail(
                Long.toUnsignedString(row.getId()), row.getCategoryCode(), row.getCategoryName(),
                row.getName(), decodeStrings(row.getAliases()), decodeStrings(row.getTags()),
                row.getSummary(), row.getCoverUrl(), row.getBodyType(), row.getDescriptionHtml(),
                row.getCareAdviceHtml(), row.getHealthTipsHtml(), row.getStatus(),
                row.getSortNo() == null ? 0 : row.getSortNo(),
                row.getVersion() == null ? 0 : row.getVersion(),
                utc(row.getCreatedAt()), utc(row.getUpdatedAt()));
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.append('"').toString();
    }

    private static String statusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        if (!BREED_STATUSES.contains(status)) invalid("status must be PUBLISHED or OFFLINE");
        return status;
    }

    private Long categoryFilter(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) return null;
        String code = categoryCodeLexeme(categoryCode.trim());
        AdminCategoryEntity row = store.read(m -> m.selectCategoryByCode(code));
        if (row == null) throw notFound();
        return row.getId();
    }

    private static String keywordPattern(String keyword) {
        if (keyword == null) return null;
        String value = keyword.trim();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) < 2
                || value.codePointCount(0, value.length()) > KEYWORD_LIMIT) {
            invalid("keyword is outside 2..40 codepoints");
        }
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    private static long breedId(String breedId) {
        Objects.requireNonNull(breedId, "breedId is required");
        if (!breedId.matches("[1-9][0-9]{0,18}")) {
            invalid("breedId must be a decimal Snowflake String");
        }
        return Long.parseLong(breedId);
    }

    private static String requiredCode(String categoryCode) {
        // v0.4: the PRD five-code enum is open — the code lexeme is validated here and the
        // dictionary row itself is proven inside the write work (404 on unknown).
        return categoryCodeLexeme(categoryCode);
    }

    /** v0.4 dictionary code lexeme: 2..16 chars of A-Z, digits and underscore. */
    private static String categoryCodeLexeme(String categoryCode) {
        if (categoryCode == null || !categoryCode.matches("[A-Z][A-Z0-9_]{1,15}")) {
            invalid("categoryCode must be 2..16 chars of A-Z, digits and underscore");
        }
        return categoryCode;
    }

    private static String displayText(String raw, int min, int max, String field) {
        if (raw == null) invalid(field + " is required");
        String value = raw.trim();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            invalid(field + " is outside " + min + ".." + max + " codepoints");
        }
        return value;
    }

    private static String optionalText(String raw, int max, String field) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > max) {
            invalid(field + " exceeds " + max + " codepoints");
        }
        return value;
    }

    private static List<String> strings(List<String> raw, int maxItems, int maxLength, String field) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > maxItems) invalid(field + " exceeds " + maxItems + " items");
        return raw.stream().map(item -> {
            if (item == null) invalid(field + " items must be text");
            String value = item.trim();
            if (value.isEmpty() || value.codePointCount(0, value.length()) > maxLength) {
                invalid(field + " item is empty or exceeds " + maxLength + " codepoints");
            }
            return value;
        }).distinct().toList();
    }

    private static String encodeStrings(List<String> values) {
        if (values.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(jsonString(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static List<String> decodeStrings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return List.copyOf(values);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored encyclopedia JSON array is not readable", failure);
        }
    }

    private static int page(Integer value) {
        if (value == null) return 1;
        if (value < 1 || value > 10_000) invalid("page is outside 1..10000");
        return value;
    }

    private static int pageSize(Integer value) {
        if (value == null) return 20;
        if (value < 1 || value > MAX_PAGE_SIZE) invalid("pageSize is outside 1.." + MAX_PAGE_SIZE);
        return value;
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound() {
        return new ApiException(CommonApiCodes.NOT_FOUND, "资源不存在");
    }

    private static ApiException conflict() {
        return new ApiException(CommonApiCodes.CONFLICT, "当前状态或版本冲突，请刷新后核对");
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
