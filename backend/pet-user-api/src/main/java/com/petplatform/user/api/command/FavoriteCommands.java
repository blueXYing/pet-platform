package com.petplatform.user.api.command;

import com.petplatform.common.CommandContext;

/**
 * Contract 58 favorite commands (32号裁决第二节批次): user-private SERVICE/STORE favorites.
 * targetType is exactly "SERVICE" or "STORE"; the kernel validates the target through the
 * catalog visibility APIs before writing, so what the user saw is what they can favorite.
 */
public final class FavoriteCommands {
    private FavoriteCommands() {}

    /** Add (or replay an existing) favorite for the current session subject. */
    public record Add(String targetType, String targetId, CommandContext context) {}

    /** Cancel the favorite for the (targetType, targetId) pair; absent pairs succeed. */
    public record Cancel(String targetType, String targetId, CommandContext context) {}

    /** First-create receipt; replays and business duplicates return the current row. */
    public record FavoriteReceipt(
            String favoriteId, String targetType, String targetId,
            java.time.OffsetDateTime favoritedAt, boolean created) {}

    /** Cancel receipt; removed=false when the pair was not favorited at commit time. */
    public record CancelReceipt(String targetType, String targetId, boolean removed) {}
}
