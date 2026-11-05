package com.petplatform.payment.biz.center;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Contract 75 §4.1 server-side policy per bizType: amount ceiling and maximum payment lifetime.
 * Only types registered here can create a payment; stage 1 registers DEMO only when its switch is on.
 */
public final class PaymentCenterBizPolicy {
    public static final String DEMO = "DEMO";
    static final Duration MIN_REMAINING = Duration.ofMinutes(1);
    private static final Pattern BIZ_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern BIZ_NO = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public record Limits(BigDecimal maxAmount, Duration maxLifetime) {
        public Limits {
            Objects.requireNonNull(maxAmount);
            Objects.requireNonNull(maxLifetime);
            if (maxAmount.signum() <= 0 || maxAmount.scale() > 2
                    || maxLifetime.compareTo(MIN_REMAINING) <= 0 || maxLifetime.compareTo(Duration.ofHours(24)) > 0)
                throw new IllegalArgumentException("payment center business limits are invalid");
        }
    }

    private final Map<String, Limits> limits;

    public PaymentCenterBizPolicy(Map<String, Limits> limits) {
        Objects.requireNonNull(limits);
        limits.keySet().forEach(type -> {
            if (type == null || !BIZ_TYPE.matcher(type).matches())
                throw new IllegalArgumentException("payment center bizType is invalid");
        });
        this.limits = Map.copyOf(limits);
    }

    /** Stage 1: DEMO with a 10-minute lifetime (Contract 75 §7). */
    public static PaymentCenterBizPolicy demoOnly(BigDecimal maxAmount) {
        return new PaymentCenterBizPolicy(Map.of(DEMO, new Limits(maxAmount, Duration.ofMinutes(10))));
    }

    public static PaymentCenterBizPolicy none() { return new PaymentCenterBizPolicy(Map.of()); }

    public boolean registered(String bizType) { return bizType != null && limits.containsKey(bizType); }

    /** Static command checks that do not depend on the clock. */
    Limits requireCommand(String bizType, String bizNo, BigDecimal amount) {
        if (!registered(bizType)) throw invalid("payment center bizType is not enabled");
        requireBizNo(bizNo);
        Limits value = limits.get(bizType);
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(value.maxAmount()) > 0) throw invalid("payment center amount is invalid");
        return value;
    }

    /** {@code now + 1 minute <= expireAt <= now + maxLifetime}, judged against the database clock. */
    static void requireWindow(Limits value, OffsetDateTime expireAt, OffsetDateTime now) {
        if (expireAt == null || now == null || expireAt.isBefore(now.plus(MIN_REMAINING))
                || expireAt.isAfter(now.plus(value.maxLifetime())))
            throw invalid("payment center deadline is outside the business window");
    }

    static void requireBizNo(String bizNo) {
        if (bizNo == null || !BIZ_NO.matcher(bizNo).matches()) throw invalid("payment center bizNo is invalid");
    }

    static void requireBizType(String bizType) {
        if (bizType == null || !BIZ_TYPE.matcher(bizType).matches()) throw invalid("payment center bizType is invalid");
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }
}
