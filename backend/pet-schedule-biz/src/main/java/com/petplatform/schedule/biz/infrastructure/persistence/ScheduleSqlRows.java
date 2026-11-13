package com.petplatform.schedule.biz.infrastructure.persistence;

import com.petplatform.common.DecimalPublicIdCodec;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/** Keeps MyBatis row conversion and nullable write parameters in one place. */
public final class ScheduleSqlRows {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private ScheduleSqlRows() {}

    public static Map<String, Object> values(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("unpaired SQL value");
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }

    public static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("missing " + column);
        return number.longValue();
    }

    public static Long nullableNumber(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Number) value).longValue();
    }

    public static String id(Map<String, Object> row, String column) {
        long value = number(row, column);
        if (value <= 0) throw new IllegalArgumentException("invalid " + column);
        return IDS.toApi(value);
    }

    public static String version(Map<String, Object> row, String column) {
        long value = number(row, column);
        if (value < 0) throw new IllegalArgumentException("invalid " + column);
        return Long.toString(value);
    }

    public static String text(Map<String, Object> row, String column) {
        return (String) row.get(column);
    }

    /** MySQL JSON column read: drivers may answer String or byte[] (CCR-SCH-SLOT-003). */
    public static String json(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) return null;
        if (value instanceof String text) return text;
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("invalid " + column);
    }

    public static LocalDateTime dateTime(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof Timestamp stamp) return stamp.toLocalDateTime();
        throw new IllegalArgumentException("invalid " + column);
    }

    public static OffsetDateTime at(Map<String, Object> row, String column) {
        LocalDateTime value = dateTime(row, column);
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

}
