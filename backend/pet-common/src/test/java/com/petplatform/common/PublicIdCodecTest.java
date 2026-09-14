package com.petplatform.common;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.support.SequenceIdStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicIdCodecTest {
    private final PublicIdCodec codec = new DecimalPublicIdCodec();

    @ParameterizedTest
    @ValueSource(strings = {"1", "9007199254740993", "9223372036854775807"})
    void exactStringRoundTripBeyondJavascriptSafeInteger(String value) {
        assertEquals(value, codec.toApi(codec.fromApi(value)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "+1", "01", "1.0", "1e3", " 1", "1 ", "1\n",
            "9223372036854775808", "99999999999999999999", "１", "١"})
    void rejectsNoncanonicalOrOutOfRangeInput(String value) {
        assertThrows(IllegalArgumentException.class, () -> codec.fromApi(value));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonpositiveInternalId(long id) {
        assertThrows(IllegalArgumentException.class, () -> codec.toApi(id));
    }

    @Test
    void explicitlyFiniteTestStubDoesNotOverflowOrBecomeProductionGenerator() {
        long[] original = {9007199254740993L, Long.MAX_VALUE};
        SnowflakeIdGenerator stub = new SequenceIdStub(original);
        original[0] = 1;
        assertEquals("9007199254740993", codec.toApi(stub.nextId()));
        assertEquals(Long.MAX_VALUE, stub.nextId());
        assertThrows(IllegalStateException.class, stub::nextId);
        assertThrows(IllegalArgumentException.class, () -> new SequenceIdStub(0));
        assertThrows(IllegalArgumentException.class, SequenceIdStub::new);
    }
}
