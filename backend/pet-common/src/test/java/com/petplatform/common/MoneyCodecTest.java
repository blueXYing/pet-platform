package com.petplatform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyCodecTest {
    private final MoneyCodec codec = new FixedDecimalMoneyCodec();

    @ParameterizedTest
    @ValueSource(strings = {"128", "128.0", "128.00"})
    void equivalentLegalInputsBecomeExactScaleTwo(String value) {
        assertEquals(new BigDecimal("128.00"), codec.parse(value));
        assertEquals("128.00", codec.format(codec.parse(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999999999999999.99", "-9999999999999999.99", "-0.01", "0.00"})
    void decimalRangeDoesNotDecideBusinessEligibility(String value) {
        assertEquals(value, codec.format(codec.parse(value)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"128.", "128.000", "128.001", "1e2", "+1", "01", "00.00",
            " 1", "1 ", "1\n", "１", "١.١", "-0", "-0.0", "-0.00", ".50",
            "10000000000000000.00", "-10000000000000000.00"})
    void rejectsInvalidLexicalInputWithoutRounding(String value) {
        assertThrows(IllegalArgumentException.class, () -> codec.parse(value));
    }

    @Test
    void formattingValuesIsDistinctFromParsingText() {
        assertEquals("128.00", codec.format(new BigDecimal("128.000")));
        assertEquals("100.00", codec.format(new BigDecimal("1E+2")));
        // BigDecimal has already lost the original negative-zero spelling.
        assertEquals("0.00", codec.format(new BigDecimal("-0.00")));
        BigDecimal value = new BigDecimal("128.001");
        assertThrows(IllegalArgumentException.class, () -> codec.format(value));
        assertEquals(new BigDecimal("128.001"), value);
        assertThrows(IllegalArgumentException.class,
                () -> codec.format(new BigDecimal("10000000000000000.00")));
        assertThrows(IllegalArgumentException.class, () -> codec.format(null));
    }
}
