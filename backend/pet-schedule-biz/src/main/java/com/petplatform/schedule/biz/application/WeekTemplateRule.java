package com.petplatform.schedule.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * CCR-SCH-SLOT-003 weekly cycling template model (approved 2026-11-13): one persistent
 * per-store configuration row cycling all 365 days by weekday — the read side expands it
 * into virtual segments (availability/selection items = real windows ∪ template expansion
 * bounded by now+maxDays, template segments overlapping a real OPEN/SOLD_OUT window are
 * skipped), and the booking hold materializes a hit segment into a real minute-level
 * window inside its own transaction before claiming. The window table itself keeps no
 * periodic columns (dayOfWeek/repeatWeekly/copyNextWeek stay forbidden, SSOT §12.1 as
 * amended); an absent row means "all seven days closed" with logical version "0" and is
 * never backfilled. days[0]=Monday .. days[6]=Sunday (ISO weekday order).
 */
public final class WeekTemplateRule {
    /** 53号 §3.6 writer-side envelope: per day at most 24 segments, capacity 1..999. */
    public static final int MAX_SEGMENTS_PER_DAY = 24;
    public static final int MIN_SEGMENT_CAPACITY = 1;
    public static final int MAX_SEGMENT_CAPACITY = 999;
    public static final int DAYS_PER_WEEK = 7;

    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule());

    static {
        JSON.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** One template segment: [start,end) inside a single business-zone day, own capacity. */
    public record Segment(LocalTime start, LocalTime end, int capacity) {}

    /** One weekday of the cycle; a disabled day (or an empty segment list) is closed. */
    public record Day(boolean enabled, List<Segment> segments) {
        public Day {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    /** The whole 7-day cycle; exactly {@link #DAYS_PER_WEEK} days, Monday first. */
    public record Template(List<Day> days) {
        public Template {
            days = List.copyOf(days);
        }
    }

    /** One expanded virtual segment (read side) or one hit segment (materialization). */
    public record Expanded(OffsetDateTime start, OffsetDateTime end, int capacity) {}

    private WeekTemplateRule() {}

    /** The absent-row answer: seven closed days (never persisted by reads). */
    public static Template absent() {
        List<Day> days = new ArrayList<>(DAYS_PER_WEEK);
        for (int index = 0; index < DAYS_PER_WEEK; index++) {
            days.add(new Day(false, List.of()));
        }
        return new Template(days);
    }

    /**
     * Writer-side validation (400 COMMON_INVALID_ARGUMENT): exactly 7 days; per day
     * 0..24 segments, each start<end at minute precision, non-overlapping (half-open
     * adjacency allowed), capacity 1..999. A disabled day may still carry segments —
     * they stay stored verbatim but never expand.
     */
    public static void validate(List<Day> days) {
        if (days == null || days.size() != DAYS_PER_WEEK) {
            invalid("week template must list exactly 7 days");
        }
        for (Day day : days) {
            if (day == null) invalid("week template days must not be null");
            List<Segment> segments = day.segments();
            if (segments.size() > MAX_SEGMENTS_PER_DAY) {
                invalid("a week template day allows at most " + MAX_SEGMENTS_PER_DAY
                        + " segments");
            }
            for (Segment segment : segments) {
                if (segment == null || segment.start() == null || segment.end() == null) {
                    invalid("each week template segment must have start before end");
                }
            }
            List<Segment> ordered = new ArrayList<>(segments);
            ordered.sort(Comparator.comparing(Segment::start));
            for (Segment segment : ordered) {
                if (!segment.end().isAfter(segment.start())) {
                    invalid("each week template segment must have start before end");
                }
                if (segment.capacity() < MIN_SEGMENT_CAPACITY
                        || segment.capacity() > MAX_SEGMENT_CAPACITY) {
                    invalid("week template segment capacity must be between "
                            + MIN_SEGMENT_CAPACITY + " and " + MAX_SEGMENT_CAPACITY);
                }
            }
            for (int index = 1; index < ordered.size(); index++) {
                // Overlap only: start strictly before the previous end. Half-open adjacency
                // (start == prev.end) is legal (same fixed sense as the batch-generate check).
                if (ordered.get(index).start().isBefore(ordered.get(index - 1).end())) {
                    invalid("week template segments of one day must not overlap");
                }
            }
        }
    }

    /** Canonical storage form of a validated cycle (stable key order, "HH:mm" times). */
    public static String write(List<Day> days) {
        try {
            // LocalTime must serialize as HH:mm — the reader lexicon rejects the driver's
            // default "HH:mm:ss" (acceptance round caught the round-trip on CI MySQL).
            com.fasterxml.jackson.databind.ObjectMapper writer = JSON.copy();
            com.fasterxml.jackson.databind.module.SimpleModule module =
                    new com.fasterxml.jackson.databind.module.SimpleModule();
            module.addSerializer(java.time.LocalTime.class,
                    new com.fasterxml.jackson.databind.ser.std.StdSerializer<>(java.time.LocalTime.class) {
                        @Override
                        public void serialize(java.time.LocalTime value,
                                com.fasterxml.jackson.core.JsonGenerator generator,
                                com.fasterxml.jackson.databind.SerializerProvider provider)
                                throws java.io.IOException {
                            generator.writeString(value.format(
                                    java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                        }
                    });
            writer.registerModule(module);
            return writer.writeValueAsString(days == null ? List.of() : days);
        } catch (Exception failure) {
            throw unavailableFailure("week template serialization failed", failure);
        }
    }

    /**
     * Reader-side parse of the stored days JSON: readers trust the writer's envelope, so
     * only a structurally impossible document fails closed (503); an absent row is
     * expressed by the caller before parsing (null json).
     */
    public static Template parse(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray() || root.size() != DAYS_PER_WEEK) bad();
            List<Day> days = new ArrayList<>(DAYS_PER_WEEK);
            for (JsonNode dayNode : root) {
                if (dayNode == null || !dayNode.isObject()) bad();
                JsonNode enabled = dayNode.get("enabled");
                JsonNode segmentsNode = dayNode.get("segments");
                if (enabled == null || !enabled.isBoolean()
                        || segmentsNode == null || !segmentsNode.isArray()) bad();
                List<Segment> segments = new ArrayList<>(segmentsNode.size());
                for (JsonNode segmentNode : segmentsNode) {
                    if (segmentNode == null || !segmentNode.isObject()) bad();
                    JsonNode start = segmentNode.get("start");
                    JsonNode end = segmentNode.get("end");
                    JsonNode capacity = segmentNode.get("capacity");
                    if (start == null || end == null || capacity == null
                            || !start.isTextual() || !end.isTextual()
                            || !capacity.isIntegralNumber() || !capacity.canConvertToInt()) {
                        bad();
                    }
                    LocalTime startTime = parseTime(start.asText());
                    LocalTime endTime = parseTime(end.asText());
                    int capacityValue = capacity.asInt();
                    if (startTime == null || endTime == null || !endTime.isAfter(startTime)
                            || capacityValue < MIN_SEGMENT_CAPACITY
                            || capacityValue > MAX_SEGMENT_CAPACITY) bad();
                    segments.add(new Segment(startTime, endTime, capacityValue));
                }
                days.add(new Day(enabled.asBoolean(), List.copyOf(segments)));
            }
            return new Template(List.copyOf(days));
        } catch (ApiException known) {
            throw known;
        } catch (Exception failure) {
            throw unavailableFailure("week template document is unreadable", failure);
        }
    }

    /**
     * Read-time expansion (CCR-SCH-SLOT-003 §1): virtual segments of the enabled weekdays
     * over the queried calendar days [fromDate..toDate] in the business zone, kept only
     * when the segment has not finished, sits inside the store bookable range
     * (now+lead .. now+maxDays) and does not overlap any real OPEN/SOLD_OUT window
     * (half-open intersection, adjacency allowed). Result is start-ascending.
     */
    public static List<Expanded> expand(
            Template template,
            LocalDate fromDate,
            LocalDate toDate,
            Instant now,
            BookingWindowRule.Range range,
            List<Expanded> realOpenWindows,
            ZoneId zone) {
        Objects.requireNonNull(template, "template is required");
        List<Expanded> result = new ArrayList<>();
        for (LocalDate day = fromDate; !day.isAfter(toDate); day = day.plusDays(1)) {
            Day rule = template.days().get(day.getDayOfWeek().getValue() - 1);
            if (rule == null || !rule.enabled()) continue;
            for (Segment segment : rule.segments()) {
                OffsetDateTime start = day.atTime(segment.start())
                        .atZone(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
                OffsetDateTime end = day.atTime(segment.end())
                        .atZone(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
                if (!end.toInstant().isAfter(now)) continue; // finished segments never return
                if (!BookingWindowRule.within(start.toInstant(), now, range)) {
                    continue; // outside the bookable range stays hidden
                }
                if (overlaps(start, end, realOpenWindows)) {
                    continue; // a real OPEN/SOLD_OUT window already covers (part of) this time
                }
                result.add(new Expanded(start, end, segment.capacity()));
            }
        }
        result.sort(Comparator.comparing(Expanded::start).thenComparing(Expanded::end));
        return result;
    }

    /**
     * Materialization hit test (CCR-SCH-SLOT-003 §1): the target instant (business zone)
     * must fall inside an enabled weekday's segment; with a required end the whole target
     * interval must fit inside the segment. Returns the hit segment or null.
     */
    public static Expanded segmentHit(Template template, OffsetDateTime targetStart,
            OffsetDateTime targetEnd, ZoneId zone) {
        Objects.requireNonNull(template, "template is required");
        if (targetStart == null) return null;
        var zonedStart = targetStart.atZoneSameInstant(zone);
        LocalDate day = zonedStart.toLocalDate();
        LocalTime from = zonedStart.toLocalTime();
        if (day.getDayOfWeek().getValue() > template.days().size()) return null;
        Day rule = template.days().get(day.getDayOfWeek().getValue() - 1);
        if (rule == null || !rule.enabled()) return null;
        OffsetDateTime targetStartUtc =
                targetStart.withOffsetSameInstant(ZoneOffset.UTC);
        for (Segment segment : rule.segments()) {
            if (from.isBefore(segment.start())) continue;
            OffsetDateTime segmentStart = day.atTime(segment.start())
                    .atZone(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime segmentEnd = day.atTime(segment.end())
                    .atZone(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            if (targetEnd != null) {
                OffsetDateTime targetEndUtc =
                        targetEnd.withOffsetSameInstant(ZoneOffset.UTC);
                if (!targetStartUtc.isBefore(segmentStart)
                        && !targetEndUtc.isAfter(segmentEnd)) {
                    return new Expanded(segmentStart, segmentEnd, segment.capacity());
                }
            } else if (targetStartUtc.isBefore(segmentEnd)) {
                return new Expanded(segmentStart, segmentEnd, segment.capacity());
            }
        }
        return null;
    }

    /**
     * Deterministic virtual windowId for an unmaterialized template segment (the C-end
     * selection item shape keeps its windowId field; PICKUP_DELIVERY order creation
     * requires non-null selected window IDs). Encodes UTC epoch-minute × 2048 +
     * duration-minute: values stay far below production Snowflake IDs (whose epoch offset
     * makes them ≥ 10^17) and never collide with the small sequential IDs of isolated
     * test fixtures. The hold never decodes it — an unknown selected ID plus the segment
     * time hit simply materializes a real window.
     */
    public static long virtualWindowId(OffsetDateTime start, OffsetDateTime end) {
        long startMinute = start.toInstant().getEpochSecond() / 60;
        long duration = (end.toEpochSecond() - start.toEpochSecond()) / 60;
        if (startMinute <= 0 || duration <= 0 || duration >= 2048) {
            throw new IllegalArgumentException("virtual window interval is invalid");
        }
        return startMinute * 2048 + duration;
    }

    /** Half-open [start,end) intersection against a real open window, adjacency allowed. */
    public static boolean overlaps(OffsetDateTime start, OffsetDateTime end,
            List<Expanded> realOpenWindows) {
        for (Expanded window : realOpenWindows) {
            if (window.start().isBefore(end) && start.isBefore(window.end())) return true;
        }
        return false;
    }

    private static LocalTime parseTime(String value) {
        if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) return null;
        return LocalTime.parse(value);
    }

    private static void bad() {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "week template facts are invalid");
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException unavailableFailure(String message, Throwable cause) {
        ApiException failure = new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }
}
