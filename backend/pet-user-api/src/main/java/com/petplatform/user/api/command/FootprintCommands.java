package com.petplatform.user.api.command;

import com.petplatform.common.CommandContext;

/**
 * Contract 58 footprint commands (32号裁决第二节批次). The C client records one footprint per
 * service/store detail view; the kernel upserts by (user, targetType, targetId) so a repeated
 * view refreshes last_viewed_at instead of adding a row.
 */
public final class FootprintCommands {
    private FootprintCommands() {}

    /** Record one view of the target; target existence/visibility is validated first. */
    public record Record(String targetType, String targetId, CommandContext context) {}

    /** Delete one footprint row by id; absent ids succeed (idempotent removal). */
    public record Delete(String footprintId, CommandContext context) {}

    /** Upsert receipt; first-ever view carries created=true. */
    public record RecordReceipt(
            String footprintId, String targetType, String targetId,
            java.time.OffsetDateTime lastViewedAt, boolean created) {}

    /** Delete receipt; deleted=false when the row was already gone. */
    public record DeleteReceipt(String footprintId, boolean deleted) {}
}
