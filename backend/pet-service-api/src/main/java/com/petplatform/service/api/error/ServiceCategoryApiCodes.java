package com.petplatform.service.api.error;

/**
 * Stable service category dictionary codes (contract 74 §8). HTTP mapping lives in the terminal
 * adapter: all three codes are 404/409/409 respectively.
 */
public final class ServiceCategoryApiCodes {
    private ServiceCategoryApiCodes() {}

    /** The path {@code categoryCode} does not resolve to a dictionary row (404). */
    public static final String CATEGORY_NOT_FOUND = "CATEGORY_NOT_FOUND";

    /** Create rejected because the immutable code already exists (409). */
    public static final String CATEGORY_CODE_CONFLICT = "CATEGORY_CODE_CONFLICT";

    /** {@code expectedVersion} no longer matches the stored optimistic version (409). */
    public static final String CATEGORY_VERSION_CONFLICT = "CATEGORY_VERSION_CONFLICT";
}
