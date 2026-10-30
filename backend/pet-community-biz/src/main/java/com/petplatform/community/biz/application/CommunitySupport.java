package com.petplatform.community.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.community.api.CommunityApiCodes;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.community.biz.application.port.CommunityMediaObjectStore;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort.UserCard;
import com.petplatform.community.biz.infrastructure.persistence.CommunityStore;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.CommentRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.MediaRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.PostRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.ReportRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.TopicRow;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/** Shared CCR-COM-001 kernel helpers. */
abstract class CommunitySupport {

    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SENSITIVE = Set.of(
            "赌博", "色情", "毒品", "诈骗", "代开发票", "办证", "枪支", "裸聊");

    protected final CommunityStore store;
    protected final CommunityUserDirectoryPort users;
    protected final CommunityMediaObjectStore mediaStore;
    protected final Clock clock;
    protected final String mediaBaseUrl;

    protected CommunitySupport(CommunityStore store, CommunityUserDirectoryPort users,
            CommunityMediaObjectStore mediaStore, Clock clock, String mediaBaseUrl) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.users = Objects.requireNonNull(users, "users is required");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.mediaBaseUrl = mediaBaseUrl == null ? "" : mediaBaseUrl.strip().replaceAll("/$", "");
    }

    protected LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    protected OffsetDateTime nowOffset() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    protected static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    protected static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid("id must be a decimal Snowflake String");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            invalid("id is too large");
            return 0;
        }
    }

    protected static String idString(Long value) {
        return value == null ? null : Long.toUnsignedString(value);
    }

    protected static String requireText(String value, int min, int max, String field) {
        if (value == null) invalid(field + " is required");
        String text = value.strip();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) invalid(field + " length is invalid");
        return text;
    }

    protected static String optionalText(String value, int max, String field) {
        if (value == null) return null;
        String text = value.strip();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > max) invalid(field + " length is invalid");
        return text;
    }

    protected static int page(int value) {
        if (value < 1 || value > 10_000) invalid("page is invalid");
        return value;
    }

    protected static int pageSize(int value, int max) {
        if (value < 1 || value > max) invalid("pageSize is invalid");
        return value;
    }

    protected static long operatorId(CommandContext context) {
        try {
            return Long.parseLong(context.operatorId());
        } catch (RuntimeException failure) {
            invalid("operatorId is invalid");
            return 0;
        }
    }

    protected static void requireCommand(CommandContext context, String userId) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "MINIAPP session is required");
        }
        if (userId == null || !context.operatorId().equals(userId)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "session identity mismatch");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException failure) {
            invalid("requestId is invalid");
        }
    }

    protected static String requestKey(String commandType, CommandContext context, String suffix) {
        String raw = context.operatorType() + ":" + context.operatorId() + ":"
                + context.requestId()
                + (suffix == null || suffix.isBlank() ? "" : ":" + suffix);
        return "COM:" + commandType + ":" + CommunityCanonical.hash(raw);
    }

    protected <T> T idempotent(String requestKey, String commandType, Map<String, Object> params,
            Function<String, T> parseReceipt, Supplier<T> work, Function<T, String> receiptJson) {
        CommunityCanonical canonical = CommunityCanonical.of(params);
        CommunityStore.Binding binding = admitWithRecovery(requestKey, commandType, canonical);
        CommunityStore.requireSame(binding, canonical);
        if ("SUCCEEDED".equals(binding.status())) return parseReceipt.apply(binding.receiptJson());
        if (!"IN_PROGRESS".equals(binding.status())) unavailable("idempotency binding is damaged");
        if (!binding.fresh()) conflict("community command is already in progress");
        try {
            T result = work.get();
            store.markSucceeded(requestKey, receiptJson.apply(result));
            return result;
        } catch (CommunityStore.CommitUnknown unknown) {
            CommunityStore.Binding replay = admitWithRecovery(requestKey, commandType, canonical);
            CommunityStore.requireSame(replay, canonical);
            if ("SUCCEEDED".equals(replay.status())) return parseReceipt.apply(replay.receiptJson());
            unavailable("community command commit result is unknown");
            return null;
        } catch (RuntimeException failure) {
            try {
                store.execute(mapper -> mapper.deleteBinding(requestKey));
            } catch (RuntimeException ignored) {
                // Keep the original failure; a stale row is recoverable with a new requestId.
            }
            throw failure;
        }
    }

    private CommunityStore.Binding admitWithRecovery(String requestKey, String commandType,
            CommunityCanonical canonical) {
        try {
            return store.admit(requestKey, commandType, canonical);
        } catch (CommunityStore.CommitUnknown first) {
            try {
                return store.admit(requestKey, commandType, canonical);
            } catch (CommunityStore.CommitUnknown second) {
                unavailable("community idempotency admission result is unknown");
                return null;
            }
        }
    }

    protected static String receiptJson(CommunityViews.Receipt receipt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", receipt.id());
        out.put("status", receipt.status());
        out.put("created", receipt.created());
        out.put("occurredAt", receipt.occurredAt().toString());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("community receipt serialization failed", failure);
        }
    }

    protected static CommunityViews.Receipt parseReceipt(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            return new CommunityViews.Receipt(root.path("id").asText(null),
                    root.path("status").asText(null), root.path("created").asBoolean(),
                    OffsetDateTime.parse(root.path("occurredAt").asText()));
        } catch (Exception failure) {
            unavailable("stored community receipt is not readable");
            return null;
        }
    }

    protected static String interactionJson(CommunityViews.InteractionResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postId", result.postId());
        out.put("active", result.active());
        out.put("count", result.count());
        out.put("changed", result.changed());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("community interaction serialization failed", failure);
        }
    }

    protected static CommunityViews.InteractionResult parseInteraction(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            return new CommunityViews.InteractionResult(root.path("postId").asText(),
                    root.path("active").asBoolean(), root.path("count").asInt(),
                    root.path("changed").asBoolean());
        } catch (Exception failure) {
            unavailable("stored community interaction is not readable");
            return null;
        }
    }

    protected static String mediaJson(CommunityViews.MediaView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mediaId", view.mediaId());
        out.put("url", view.url());
        out.put("contentType", view.contentType());
        out.put("bytes", view.bytes());
        out.put("width", view.width());
        out.put("height", view.height());
        out.put("sha256", view.sha256());
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception failure) {
            throw new IllegalStateException("community media serialization failed", failure);
        }
    }

    protected static CommunityViews.MediaView parseMedia(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            return new CommunityViews.MediaView(root.path("mediaId").asText(),
                    root.path("url").asText(), root.path("contentType").asText(),
                    root.path("bytes").asLong(), root.path("width").asInt(),
                    root.path("height").asInt(), root.path("sha256").asText());
        } catch (Exception failure) {
            unavailable("stored community media is not readable");
            return null;
        }
    }

    protected static CommunityViews.TopicView topic(TopicRow row) {
        return row == null ? null : new CommunityViews.TopicView(row.topicCode, row.name,
                row.description, row.sortWeight == null ? 0 : row.sortWeight);
    }

    protected static CommunityViews.AuthorView author(UserCard card, String userId) {
        if (card != null) {
            return new CommunityViews.AuthorView(card.userId(), card.nickname(), card.avatarUrl());
        }
        return new CommunityViews.AuthorView(userId, "宠友", null);
    }

    protected Map<Long, UserCard> userCards(Collection<Long> ids) {
        List<String> strings = new ArrayList<>(new LinkedHashSet<>(ids)).stream()
                .map(Long::toUnsignedString).toList();
        if (strings.isEmpty()) return Map.of();
        try {
            Map<String, UserCard> found = users.lookup(strings);
            Map<Long, UserCard> out = new LinkedHashMap<>();
            if (found != null) {
                found.forEach((key, card) -> out.put(Long.parseLong(key), card));
            }
            return out;
        } catch (RuntimeException failure) {
            // Nicknames are presentation facts; a transient user-read outage must not blank
            // the whole feed. The fallback name is the honest anonymous projection.
            return Map.of();
        }
    }

    protected CommunityViews.AuthorView authorOf(PostRow row, Map<Long, UserCard> cards) {
        return author(cards.get(row.authorUserId), Long.toUnsignedString(row.authorUserId));
    }

    protected static List<String> imageIds(PostRow row) {
        if (row.imageIds == null || row.imageIds.isBlank()) return List.of();
        try {
            JsonNode node = JSON.readTree(row.imageIds);
            if (node == null || !node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) out.add(item.asText());
                else if (item.isIntegralNumber() && item.asLong() > 0) out.add(Long.toUnsignedString(item.asLong()));
            }
            return List.copyOf(out);
        } catch (Exception failure) {
            return List.of();
        }
    }

    protected List<String> imageUrls(PostRow row) {
        return imageIds(row).stream().map(this::mediaUrl).toList();
    }

    protected String mediaUrl(String mediaId) {
        String path = "/api/v1/c/community/media/" + mediaId;
        return mediaBaseUrl.isBlank() ? path : mediaBaseUrl + path;
    }

    protected CommunityViews.PostCard postCard(PostRow row, UserCard card,
            Map<Long, UserCard> allCards, String viewerId) {
        long viewer = viewerId == null ? 0 : id(viewerId);
        boolean liked = viewer != 0 && store.read(m -> m.selectLike(row.id, viewer)) != null;
        boolean favorited = viewer != 0 && store.read(m -> m.selectFavorite(row.id, viewer)) != null;
        boolean following = viewer != 0 && !Long.toUnsignedString(row.authorUserId).equals(viewerId)
                && store.read(m -> m.selectFollow(viewer, row.authorUserId)) != null;
        return new CommunityViews.PostCard(
                Long.toUnsignedString(row.id), author(card, Long.toUnsignedString(row.authorUserId)),
                row.title, row.content, imageUrls(row),
                topic(row.topicCode == null ? null : topicRow(row)),
                row.likeCount == null ? 0 : row.likeCount,
                row.commentCount == null ? 0 : row.commentCount,
                row.favoriteCount == null ? 0 : row.favoriteCount,
                row.viewCount == null ? 0 : row.viewCount, liked, favorited, following,
                offset(row.publishedAt));
    }

    private static TopicRow topicRow(PostRow row) {
        TopicRow topic = new TopicRow();
        topic.id = row.topicId;
        topic.topicCode = row.topicCode;
        topic.name = row.topicName;
        topic.description = row.topicDescription;
        topic.sortWeight = row.topicSortWeight;
        return topic;
    }

    protected static boolean visibleTo(PostRow row, String viewerId) {
        if (row == null || "DELETED".equals(row.status)) return false;
        if ("PUBLISHED".equals(row.status) && "PUBLIC".equals(row.visibility)) return true;
        return viewerId != null && viewerId.equals(Long.toUnsignedString(row.authorUserId));
    }

    protected static boolean publishable(PostRow row) {
        return row != null && "PUBLISHED".equals(row.status) && "PUBLIC".equals(row.visibility);
    }

    protected static boolean sensitive(String title, String content) {
        String text = (title == null ? "" : title) + "\n" + content;
        for (String word : SENSITIVE) if (text.contains(word)) return true;
        return false;
    }

    protected static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    protected static BufferedImage image(byte[] content) {
        try {
            return ImageIO.read(new ByteArrayInputStream(content));
        } catch (Exception failure) {
            return null;
        }
    }

    protected static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    protected static void invalidCode(String code, String message) {
        throw new ApiException(code, message);
    }

    protected static void notFound(String code, String message) {
        throw new ApiException(code, message);
    }

    protected static void conflict(String code, String message) {
        throw new ApiException(code, message);
    }

    protected static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    protected static void forbidden(String message) {
        throw new ApiException(CommonApiCodes.FORBIDDEN, message);
    }

    protected static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
