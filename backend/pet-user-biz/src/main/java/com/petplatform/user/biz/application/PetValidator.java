package com.petplatform.user.biz.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** Field validation per CCR-W2-API-001 user domain 0.1; violations are IllegalArgumentException (HTTP: 400). */
final class PetValidator {
    private PetValidator() {}

    static final Set<String> PET_TYPES = Set.of("DOG", "CAT", "OTHER");
    static final Set<String> SEXES = Set.of("MALE", "FEMALE", "UNKNOWN");
    static final Set<String> STERILIZATION = Set.of("INTACT", "NEUTERED", "UNKNOWN");
    static final Set<String> VACCINE = Set.of("NONE", "PARTIAL", "COMPLETE", "UNKNOWN");

    static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1.." + max + " characters");
        }
        return value;
    }

    static String optional(String value, int max, String field) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be absent or contain 1.." + max + " characters");
        }
        return value;
    }

    static String optionalChoice(String value, Set<String> allowed, String field) {
        if (value == null) return null;
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed);
        }
        return value;
    }

    static String choice(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed);
        }
        return value;
    }

    static LocalDate pastDate(LocalDate value) {
        if (value == null) return null;
        if (!value.isBefore(LocalDate.now().plusDays(1))) {
            throw new IllegalArgumentException("birthDate must not be in the future");
        }
        return value;
    }

    /** Two-decimal 0.01..999.99, mirroring the public money codec rules without rounding. */
    static BigDecimal weight(BigDecimal value) {
        if (value == null) return null;
        if (value.scale() != 2 || value.compareTo(new BigDecimal("0.01")) < 0
                || value.compareTo(new BigDecimal("999.99")) > 0) {
            throw new IllegalArgumentException("weightKg must be a two-decimal value in [0.01,999.99]");
        }
        return value;
    }

    static String avatar(String value) {
        if (value == null) return null;
        if (value.length() > 512 || !value.startsWith("https://")) {
            throw new IllegalArgumentException("avatarUrl must be an https URL of at most 512 characters");
        }
        return value;
    }
}
