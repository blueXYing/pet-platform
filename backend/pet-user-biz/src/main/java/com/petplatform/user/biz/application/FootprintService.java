package com.petplatform.user.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.api.command.FootprintCommandApi;
import com.petplatform.user.api.command.FootprintCommands.Delete;
import com.petplatform.user.api.command.FootprintCommands.DeleteReceipt;
import com.petplatform.user.api.command.FootprintCommands.Record;
import com.petplatform.user.api.command.FootprintCommands.RecordReceipt;
import com.petplatform.user.api.dto.FootprintItemDTO;
import com.petplatform.user.api.dto.UserTargetDisplayDTO;
import com.petplatform.user.api.query.FootprintQueryApi;
import com.petplatform.user.biz.infrastructure.persistence.FavoriteStore;
import com.petplatform.user.biz.infrastructure.persistence.FootprintStore;
import com.petplatform.user.biz.infrastructure.persistence.entity.FootprintEntity;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Contract 58 footprint kernel. The C client records one footprint per service/store detail
 * view (浏览埋点, chosen as the least invasive option: the anonymous-browsable catalog read
 * chain stays untouched); the kernel upserts by the (user, targetType, targetId) unique key so
 * repeated views refresh last_viewed_at instead of adding rows — the disclosed dedup rule.
 * Deletes are idempotent (absent rows succeed); ordering is last_viewed_at DESC, id DESC.
 */
public final class FootprintService implements FootprintCommandApi, FootprintQueryApi {

    private final FootprintStore store;
    private final UserTargetCatalog catalog;
    private final TrackCommandFrame frame;
    private final SnowflakeIdGenerator ids;

    public FootprintService(DataSource dataSource, SnowflakeIdGenerator ids,
            UserTargetCatalog catalog) {
        this.store = new FootprintStore(dataSource);
        this.catalog = Objects.requireNonNull(catalog, "target catalog is required");
        this.frame = new TrackCommandFrame(dataSource, ids);
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
    }

    // --- commands ---

    @Override
    public FootprintCommandApi.RecordOutcome recordFootprint(Record command) {
        CommandContext context = userContext(command.context());
        String targetType = UserTargetCatalog.normalize(command.targetType());
        long userId = userId(context);
        requireActiveUser(userId);
        catalog.requireVisible(targetType, command.targetId(), readContext(context));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", targetType);
        params.put("targetId", command.targetId());
        TrackCommandFrame.Framed<RecordReceipt> outcome = frame.execute(
                "footprint.record", context, params, RecordReceipt.class,
                ignored -> {
                    requireActiveUser(userId);
                    // Upsert under the row lock: first view inserts, repeated views touch.
                    FootprintEntity existing = store.find(userId, targetType,
                            numeric(command.targetId()));
                    if (existing != null) {
                        store.touch(existing.getId(), userId);
                        FootprintEntity touched = store.find(userId, targetType,
                                numeric(command.targetId()));
                        return new RecordReceipt(FavoriteStore.unsigned(touched.getId()),
                                targetType, command.targetId(), atUtc(touched.getLastViewedAt()),
                                false);
                    }
                    FootprintEntity row = FootprintStore.row(freshId(), userId, targetType,
                            numeric(command.targetId()));
                    store.insert(row);
                    FootprintEntity inserted = store.find(userId, targetType,
                            numeric(command.targetId()));
                    return new RecordReceipt(FavoriteStore.unsigned(inserted.getId()), targetType,
                            command.targetId(), atUtc(inserted.getLastViewedAt()), true);
                },
                ignored -> {
                    requireActiveUser(userId);
                    return null;
                });
        return new RecordOutcome(outcome.receipt(),
                outcome.receipt().created() && !outcome.replayed(), outcome.replayed());
    }

    @Override
    public FootprintCommandApi.DeleteOutcome deleteFootprint(Delete command) {
        CommandContext context = userContext(command.context());
        long userId = userId(context);
        long footprintId = numeric(command.footprintId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("footprintId", command.footprintId());
        TrackCommandFrame.Framed<DeleteReceipt> outcome = frame.execute(
                "footprint.remove", context, params, DeleteReceipt.class,
                ignored -> {
                    requireActiveUser(userId);
                    boolean deleted = store.delete(footprintId, userId);
                    return new DeleteReceipt(command.footprintId(), deleted);
                },
                ignored -> {
                    requireActiveUser(userId);
                    return null;
                });
        return new DeleteOutcome(outcome.receipt(), outcome.replayed());
    }

    // --- reads ---

    @Override
    public PageResult<FootprintItemDTO> listMyFootprints(MyFootprintListQuery query) {
        Objects.requireNonNull(query, "query is required");
        long userId = readSubject(query.context());
        int page = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? 20 : query.pageSize();
        long total = store.count(userId);
        List<FootprintItemDTO> items;
        if (total == 0) {
            items = List.of();
        } else {
            QueryContext context = readContext(query.context());
            items = store.page(userId, pageSize, (page - 1) * pageSize).stream()
                    .map(row -> toItem(row, context)).toList();
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    private FootprintItemDTO toItem(FootprintEntity row, QueryContext context) {
        UserTargetDisplayDTO display = catalog.project(row.getTargetType(),
                Long.toUnsignedString(row.getTargetId()), context);
        return new FootprintItemDTO(Long.toUnsignedString(row.getId()), row.getTargetType(),
                Long.toUnsignedString(row.getTargetId()), atUtc(row.getLastViewedAt()), display);
    }

    // --- guards ---

    private static CommandContext userContext(CommandContext context) {
        CommandContext checked = com.petplatform.common.PublicContractChecks
                .requireCommandRequestId(context);
        if (checked.operatorType() != OperatorType.USER) {
            throw new IllegalArgumentException("Footprint commands require a USER operator");
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
