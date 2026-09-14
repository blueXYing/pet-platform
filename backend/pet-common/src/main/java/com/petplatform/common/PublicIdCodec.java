package com.petplatform.common;

/** Positive Long IDs represented as canonical decimal strings at API boundaries. */
public interface PublicIdCodec {
    /** @throws IllegalArgumentException for zero or negative IDs */
    String toApi(long id);

    /** @throws IllegalArgumentException for null, noncanonical or out-of-range IDs */
    long fromApi(String id);
}
