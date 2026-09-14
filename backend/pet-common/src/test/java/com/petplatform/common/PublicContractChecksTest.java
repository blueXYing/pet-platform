package com.petplatform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicContractChecksTest {
    @Test
    void terminalUuidPreservesExactOriginalText() {
        String value = "ABCDEFAB-1234-1234-ABCD-1234567890AB";
        assertSame(value, PublicContractChecks.requireTerminalRequestId(value));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1-1-1-1-1", "11111111-1111-4111-8111-111111111111\n",
            " 11111111-1111-4111-8111-111111111111", "TASK:X:1:0", "gggggggg-1111-1111-1111-111111111111"})
    void terminalRejectsLooseUuidAndInternalKeys(String value) {
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireTerminalRequestId(value));
    }

    @Test
    void internalKeysUseUtf8BytesAndAreNotNormalized() {
        String task = "TASK:ORDER_AUTO_CONFIRM:9007199254740993:0";
        assertEquals(task, PublicContractChecks.requireRequestId(task));
        assertEquals(" key ", PublicContractChecks.requireRequestId(" key "));
        assertEquals("a".repeat(512), PublicContractChecks.requireRequestId("a".repeat(512)));
        String multi = "中".repeat(170) + "ab";
        assertEquals(multi, PublicContractChecks.requireRequestId(multi));
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireRequestId(multi + "a"));
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireRequestId("a".repeat(513)));
        assertEquals("😀", PublicContractChecks.requireRequestId("😀"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "key\n", "a\u0085", "\uD800", "\uDC00"})
    void invalidInternalKeysFailBeforeBinding(String value) {
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireRequestId(value));
    }

    @Test
    void fiveFieldContextIsDataAndFieldChecksDoNotAuthenticate() {
        assertEquals(Arrays.asList("requestId", "traceId", "operatorType", "operatorId", "source"),
                Arrays.stream(CommandContext.class.getRecordComponents()).map(c -> c.getName()).toList());
        CommandContext unresolved = new CommandContext("TASK:X:1:0", null, null, null, null);
        assertSame(unresolved, PublicContractChecks.requireCommandRequestId(unresolved));
        assertNull(unresolved.traceId()); // No fabricated trace or identity in the pure check.
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireCommandRequestId(null));
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireCommandRequestId(
                new CommandContext(null, "trace", OperatorType.USER, "1", "MINI_PROGRAM")));
    }

    @Test
    void timePrecisionPreservesOffsetAndDoesNotTruncate() {
        OffsetDateTime offset = OffsetDateTime.parse("2026-09-14T14:30:00.123000000+08:00");
        OffsetDateTime utc = OffsetDateTime.parse("2026-09-14T06:30:00.123Z");
        assertSame(offset, PublicContractChecks.requireMillisecondPrecision(offset));
        assertEquals(utc.toInstant(), offset.toInstant());
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireMillisecondPrecision(
                OffsetDateTime.parse("2026-09-14T06:30:00.123000001Z")));
        assertThrows(IllegalArgumentException.class, () -> PublicContractChecks.requireMillisecondPrecision(null));
        assertThrows(DateTimeParseException.class, () -> OffsetDateTime.parse("2026-09-14T06:30:00"));
        assertThrows(DateTimeParseException.class, () -> OffsetDateTime.parse("2026-02-30T06:30:00Z"));
    }

    @Test
    void fixedClockReproducesBeforeAtAfterWithoutPretendingToControlDatabaseTime() {
        Instant deadline = Instant.parse("2026-09-14T06:30:00Z");
        Clock clock = Clock.fixed(deadline, ZoneOffset.UTC);
        assertTrue(Clock.offset(clock, Duration.ofMillis(-1)).instant().isBefore(deadline));
        assertEquals(deadline, clock.instant());
        assertTrue(Clock.offset(clock, Duration.ofMillis(1)).instant().isAfter(deadline));
        assertEquals(OffsetDateTime.parse("2026-09-14T06:30:00Z"),
                PublicContractChecks.requireMillisecondPrecision(OffsetDateTime.now(clock)));
    }
}
