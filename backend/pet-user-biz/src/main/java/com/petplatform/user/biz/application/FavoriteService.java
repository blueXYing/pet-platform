package com.petplatform.user.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.api.command.FavoriteCommandApi;
import com.petplatform.user.api.command.FavoriteCommands;
import com.petplatform.user.api.command.FavoriteCommands.Add;
import com.petplatform.user.api.command.FavoriteCommands.Cancel;
import com.petplatform.user.api.command.FavoriteCommands.CancelReceipt;
import com.petplatform.user.api.command.FavoriteCommands.FavoriteReceipt;
import com.petplatform.user.api.dto.FavoriteItemDTO;
import com.petplatform.user.api.dto.UserTargetDisplayDTO;
import com.petplatform.user.api.query.FavoriteQueryApi;
import com.petplatform.user.biz.infrastructure.persistence.FavoriteStore;
import com.petplatform.user.biz.infrastructure.persistence.entity.FavoriteEntity;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Contract 58 favorite kernel: user-private SERVICE/STORE favorites with the platform write
 * rules — requestId idempotency (supplement 23 §5, first receipt replayed), business duplicate
 * adds collapsing onto the existing row (防重复, 200 instead of 201), cancel as a hard delete
 * that succeeds when absent, target admission through the C catalog visibility APIs, and
 * strictly session-scoped reads (created_at DESC, id DESC ordering, page/pageSize bounds).
 */
public final class FavoriteService implements FavoriteCommandApi, FavoriteQueryApi {

    private final FavoriteStore store;
    private final UserTargetCatalog catalog;
    private final TrackCommandFrame frame;
    private final SnowflakeIdGenerator ids;

    public FavoriteService(DataSource dataSource, SnowflakeIdGenerator ids,
            UserTargetCatalog catalog) {
        this.store = new FavoriteStore(dataSource);
        this.catalog = Objects.requireNonNull(catalog, "target catalog is required");
        this.frame = new TrackCommandFrame(dataSource, ids);
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
    }

    // --- commands ---

    @Override
    public FavoriteCommandApi.AddOutcome addFavorite(Add command) {
        CommandContext context = userContext(command.context());
        String targetType = UserTargetCatalog.normalize(command.targetType());
        long userId = userId(context);
        requireActiveUser(userId);
        catalog.requireVisible(targetType, command.targetId(), readContext(context));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", targetType);
        params.put("targetId", command.targetId());
        TrackCommandFrame.Framed<FavoriteReceipt> outcome = frame.execute(
                "favorite.add", context, params, FavoriteReceipt.class,
                ignored -> {
                    requireActiveUser(userId);
                    // Business duplicate guard under the row lock: a different requestId for an
                    // already-favorited target collapses onto the existing row (created=false).
                    FavoriteEntity existing = store.findForUpdate(userId, targetType,
                            numeric(command.targetId()));
                    if (existing != null) {
                        return new FavoriteReceipt(FavoriteStore.unsigned(existing.getId()),
                                targetType, command.targetId(),
                                atUtc(existing.getCreatedAt()), false);
                    }
                    FavoriteEntity row = FavoriteStore.row(freshId(), userId, targetType,
                            numeric(command.targetId()));
                    store.insert(row);
                    FavoriteEntity inserted = store.find(userId, targetType,
                            numeric(command.targetId()));
                    return new FavoriteReceipt(FavoriteStore.unsigned(inserted.getId()), targetType,
                            command.targetId(), atUtc(inserted.getCreatedAt()), true);
                },
                ignored -> {
                    requireActiveUser(userId);
                    return null;
                });
        // Replay answers 200 like the review family: only the very first execution is a 201.
        return new AddOutcome(outcome.receipt(),
                outcome.receipt().created() && !outcome.replayed(), outcome.replayed());
    }

    @Override
    public FavoriteCommandApi.CancelOutcome cancelFavorite(Cancel command) {
        CommandContext context = userContext(command.context());
        String targetType = UserTargetCatalog.normalize(command.targetType());
        long userId = userId(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", targetType);
        params.put("targetId", command.targetId());
        TrackCommandFrame.Framed<CancelReceipt> outcome = frame.execute(
                "favorite.remove", context, params, CancelReceipt.class,
                ignored -> {
                    requireActiveUser(userId);
                    boolean removed = store.delete(userId, targetType, numeric(command.targetId()));
                    return new CancelReceipt(targetType, command.targetId(), removed);
                },
                ignored -> {
                    requireActiveUser(userId);
                    return null;
                });
        return new CancelOutcome(outcome.receipt(), outcome.replayed());
    }

    // --- reads ---

    @Override
    public PageResult<FavoriteItemDTO> listMyFavorites(MyFavoriteListQuery query) {
        Objects.requireNonNull(query, "query is required");
        String targetType = query.targetType() == null ? null
                : UserTargetCatalog.normalize(query.targetType());
        long userId = readSubject(query.context());
        int page = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? 20 : query.pageSize();
        long total = store.count(userId, targetType);
        List<FavoriteItemDTO> items;
        if (total == 0) {
            items = List.of();
        } else {
            QueryContext context = readContext(query.context());
            items = store.page(userId, targetType, pageSize, (page - 1) * pageSize).stream()
                    .map(row -> toItem(row, context)).toList();
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    @Override
    public FavoriteStatus favoriteStatus(FavoriteStatusQuery query) {
        Objects.requireNonNull(query, "query is required");
        String targetType = UserTargetCatalog.normalize(query.targetType());
        long userId = readSubject(query.context());
        FavoriteEntity row = store.find(userId, targetType, numeric(query.targetId()));
        return row == null ? new FavoriteStatus(null, null)
                : new FavoriteStatus(FavoriteStore.unsigned(row.getId()), atUtc(row.getCreatedAt()));
    }

    private FavoriteItemDTO toItem(FavoriteEntity row, QueryContext context) {
        UserTargetDisplayDTO display = catalog.project(row.getTargetType(),
                FavoriteStore.unsigned(row.getTargetId()), context);
        return new FavoriteItemDTO(FavoriteStore.unsigned(row.getId()), row.getTargetType(),
                FavoriteStore.unsigned(row.getTargetId()), atUtc(row.getCreatedAt()), display);
    }

    // --- guards ---

    private static CommandContext userContext(CommandContext context) {
        CommandContext checked = com.petplatform.common.PublicContractChecks
                .requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Favorite commands require a USER operator");
        }
        return checked;
    }

    private static long userId(CommandContext context) {
        return numeric(context.operatorId());
    }

    private static QueryContext readContext(CommandContext command) {
        return new QueryContext(command.traceId(), OperatorType.USER, command.operatorId());
    }

    private static QueryContext readContext(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        return context;
    }

    private static long readSubject(QueryContext context) {
        readContext(context);
        if (context.operatorId() == null || context.operatorId().isEmpty()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return numeric(context.operatorId());
    }

    private void requireActiveUser(long userId) {
        String status = store.userStatus(userId);
        if (status == null || !"ACTIVE".equals(status)) {
            throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
        }
    }

    private long freshId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("Invalid ID from provider");
        return id;
    }

    private static long numeric(String value) {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, value + " must be numeric");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException overflow) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, value + " must be a positive BIGINT");
        }
    }

    private static java.time.OffsetDateTime atUtc(java.time.LocalDateTime wall) {
        return wall == null ? null : wall.atOffset(ZoneOffset.UTC);
    }
}
