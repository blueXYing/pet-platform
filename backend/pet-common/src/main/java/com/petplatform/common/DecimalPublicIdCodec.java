package com.petplatform.common;

import java.util.regex.Pattern;

/** Stateless conversion only; does not generate IDs or establish ownership. */
public final class DecimalPublicIdCodec implements PublicIdCodec {
    private static final Pattern DECIMAL_ID = Pattern.compile("[1-9][0-9]{0,18}");

    @Override
    public String toApi(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("ID must be a positive Long");
        }
        return Long.toString(id);
    }

    @Override
    public long fromApi(String id) {
        if (id == null || !DECIMAL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("ID must be a canonical decimal string");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("ID exceeds positive Long range");
        }
    }
}
