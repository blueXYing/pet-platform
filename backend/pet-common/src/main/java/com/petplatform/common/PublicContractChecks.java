package com.petplatform.common;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * Pure field checks, not an HTTP/authentication/idempotency adapter.
 * Callers still resolve trusted actors, current authorization and business rules.
 * CommandContext remains the existing five-field data record.
 */
public final class PublicContractChecks {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private PublicContractChecks() {}

    /** Internal deterministic keys are not required to be UUIDs; no trimming or rewriting. */
    public static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()
                || requestId.codePoints().anyMatch(Character::isISOControl)
                || hasUnpairedSurrogate(requestId)
                || requestId.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("Invalid requestId");
        }
        return requestId;
    }

    /** Only terminal HTTP X-Request-Id uses this UUID syntax check; it does not normalize case. */
    public static String requireTerminalRequestId(String requestId) {
        requireRequestId(requestId);
        if (!UUID_TEXT.matcher(requestId).matches()) {
            throw new IllegalArgumentException("Terminal requestId must have UUID syntax");
        }
        return requestId;
    }

    /** Validates only the universally required requestId, not actor/source trust or trace issuance. */
    public static CommandContext requireCommandRequestId(CommandContext context) {
        if (context == null) {
            throw new IllegalArgumentException("CommandContext is required");
        }
        requireRequestId(context.requestId());
        return context;
    }

    /** No truncation or rounding; preserves the input offset and instant. */
    public static OffsetDateTime requireMillisecondPrecision(OffsetDateTime value) {
        if (value == null || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("Time must have exact millisecond precision");
        }
        return value;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
