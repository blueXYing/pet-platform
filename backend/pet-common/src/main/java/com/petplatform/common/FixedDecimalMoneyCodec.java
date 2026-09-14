package com.petplatform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Pure, immutable codec. It applies no discounts, business rounding or HTTP mapping. */
public final class FixedDecimalMoneyCodec implements MoneyCodec {
    private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");
    private static final Pattern FIXED = Pattern.compile("-?(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,2})?");

    @Override
    public BigDecimal parse(String fixedDecimal) {
        if (fixedDecimal == null || !FIXED.matcher(fixedDecimal).matches()) {
            throw new IllegalArgumentException("Money must be DECIMAL(18,2) plain text");
        }
        BigDecimal amount = new BigDecimal(fixedDecimal);
        if (fixedDecimal.startsWith("-") && amount.signum() == 0) {
            throw new IllegalArgumentException("Negative zero is not canonical money");
        }
        return exactScale(amount);
    }

    @Override
    public String format(BigDecimal value) {
        return exactScale(value).toPlainString();
    }

    private static BigDecimal exactScale(BigDecimal value) {
        if (value == null || value.abs().compareTo(MAX) > 0) {
            throw new IllegalArgumentException("Money exceeds DECIMAL(18,2) range or is null");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Money must not require rounding");
        }
    }
}
