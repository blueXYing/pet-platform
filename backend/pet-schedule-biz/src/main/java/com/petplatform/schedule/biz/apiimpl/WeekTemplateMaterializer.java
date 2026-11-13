package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.schedule.biz.application.AvailabilityQueryService;
import com.petplatform.schedule.biz.application.WeekTemplateRule;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleMybatis;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleWriteMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * CCR-SCH-SLOT-003 order-time materialization: when the booking hold (38号) or the
 * reschedule swap (46号) targets a time with no real OPEN/SOLD_OUT window, a hit weekly
 * template segment becomes a real minute-level store-wide window inside the caller's
 * guarded transaction (version 0, audit WINDOW_CREATE with the SYSTEM actor, capacity =
 * segment capacity), after which the normal claim path runs unchanged — every protection
 * (SOLD_OUT derivation, refund release, reschedule invariants, batch close) then acts on
 * a real window row. Already-existing windows are never recreated; editing the template
 * never touches materialized rows.
 *
 * <p>The selected windowId resolution keeps the historical proof semantics intact: an ID
 * that matches an existing window row (any status — closed windows keep their rows) is
 * passed through untouched, so a stale real selection still fails exactly as before; only
 * an ID matching no row at all (the deterministic virtual ID the selection read minted
 * for an unmaterialized segment) is rewritten to the covering/materialized window. A time
 * with neither a covering window nor a template hit falls through and the capacity proof
 * answers its usual conflict (external error surface unchanged, 38号 internal step).</p>
 */
final class WeekTemplateMaterializer {
    /** schedule_write_action actor_user_id for system actions (no user drives them). */
    private static final long SYSTEM_ACTOR = 0L;

    private final ScheduleWriteMapper mapper;
    private final SnowflakeIdGenerator ids;

    WeekTemplateMaterializer(DataSource source, SnowflakeIdGenerator ids) {
        this.mapper = ScheduleMybatis.template(Objects.requireNonNull(source))
                .getMapper(ScheduleWriteMapper.class);
        this.ids = Objects.requireNonNull(ids, "ids is required");
    }

    /**
     * Resolves the claimable window for one hold/swap target and materializes it from the
     * weekly template when absent (same transaction).
     *
     * @param storeId numeric store of the hold
     * @param merchantId numeric merchant owning the store (materialized windows carry it)
     * @param targetStart claim target start (appointment start, or pickup/return start)
     * @param targetEnd claim target end, or null for a point-in-window target
     * @param selectedId caller-selected windowId (public string) or null
     * @param requestId the hold/swap X-Request-Id (audit traceability)
     * @param traceId optional trace id for the audit row
     * @param now the caller's authoritative instant (row timestamps, audit occurredAt)
     * @return the effective windowId (public string) — the original selection when its row
     *     exists, the covering window for a virtual selection, or the materialized window;
     *     null when nothing claimable exists (the proof then answers its usual conflict)
     */
    String ensureClaimableWindow(
            long storeId,
            long merchantId,
            OffsetDateTime targetStart,
            OffsetDateTime targetEnd,
            String selectedId,
            String requestId,
            String traceId,
            Instant now) {
        // Same session hygiene as the write store: UTC wall time for DATETIME parameters.
        mapper.setTimeZoneUtc();
        mapper.setLockWaitTimeout2Seconds();
        if (selectedId != null) {
            Long selectedKey = parseId(selectedId);
            if (selectedKey != null
                    && mapper.selectWindowByIdForUpdate(selectedKey) != null) {
                // The selection names an existing row (open or closed): original logic.
                return selectedId;
            }
        }
        OffsetDateTime start = targetStart.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = targetEnd == null
                ? null : targetEnd.withOffsetSameInstant(ZoneOffset.UTC);
        Map<String, Object> covering = mapper.selectCoveringOpenWindow(
                storeId, start.toLocalDateTime(),
                end == null ? null : end.toLocalDateTime());
        if (covering != null) {
            // A real open-derived window already covers the target: claim on it (a virtual
            // selection of a since-covered time resolves here; a real selection with a
            // mismatching id never reaches this branch and keeps its proof conflict).
            return ScheduleSqlRows.id(covering, "id");
        }
        Map<String, Object> templateRow = mapper.selectWeekTemplateForUpdate(storeId);
        if (templateRow == null) return null; // no template: nothing to materialize
        String days = ScheduleSqlRows.json(templateRow, "days");
        if (days == null) fail("week template facts are invalid");
        WeekTemplateRule.Expanded hit = WeekTemplateRule.segmentHit(
                WeekTemplateRule.parse(days), targetStart, targetEnd,
                AvailabilityQueryService.BUSINESS_ZONE);
        if (hit == null) return null; // the time is outside every enabled segment
        OffsetDateTime segmentStart = hit.start().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime segmentEnd = hit.end().withOffsetSameInstant(ZoneOffset.UTC);
        if (mapper.selectOpenWindowOverlapping(
                storeId, segmentStart.toLocalDateTime(),
                segmentEnd.toLocalDateTime()) != null) {
            // The whole segment crosses an existing open window: the read side would have
            // skipped it, so materializing would break the store-wide no-overlap rule.
            return null;
        }
        long windowId = nextId();
        java.time.LocalDateTime stamp = java.time.LocalDateTime.ofInstant(
                Objects.requireNonNull(now).truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        if (mapper.insertWindow(ScheduleSqlRows.values(
                "id", windowId, "merchantId", merchantId, "storeId", storeId,
                "startAt", segmentStart.toLocalDateTime(),
                "endAt", segmentEnd.toLocalDateTime(),
                "configuredCapacity", hit.capacity(), "now", stamp)) != 1) {
            fail("week template materialization failed");
        }
        // Audit WINDOW_CREATE with the SYSTEM actor (CCR-SCH-SLOT-003 §1); the window
        // version is 0 exactly like a merchant-created window.
        if (mapper.insertWriteAction(ScheduleSqlRows.values(
                "id", nextId(), "targetType", "WINDOW", "targetId", windowId,
                "merchantId", merchantId, "storeId", storeId, "action", "WINDOW_CREATE",
                "actorUserId", SYSTEM_ACTOR,
                "requestId", requestId.getBytes(StandardCharsets.UTF_8),
                "traceId", traceId, "reason", null,
                "versionBefore", null, "versionAfter", 0L, "occurredAt", stamp)) != 1) {
            fail("week template materialization audit failed");
        }
        return Long.toString(windowId);
    }

    private static Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) fail("Snowflake ID unavailable");
        return value;
    }

    private static void fail(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
