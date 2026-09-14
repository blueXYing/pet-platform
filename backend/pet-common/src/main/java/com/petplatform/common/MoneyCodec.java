package com.petplatform.common;

import java.math.BigDecimal;

/** DECIMAL(18,2) conversion; business eligibility for negative/zero values is separate. */
public interface MoneyCodec {
    /** Parses plain decimal text with at most two fraction digits, rejecting negative zero. */
    BigDecimal parse(String fixedDecimal);

    /** Returns exactly two fraction digits; never rounds a value. */
    String format(BigDecimal value);
}
