package com.petplatform.service.api.error;

/**
 * Contract 77 §7 flash-price codes (registered in the 12号 error registry). HTTP mapping lives in
 * the terminal adapter: *_NOT_FOUND -> 404, state/overlap/quota/limit/invalid -> 409,
 * DISABLED -> 503.
 */
public final class ServiceFlashPriceApiCodes {
    private ServiceFlashPriceApiCodes() {}

    /** The flash-price row does not exist or does not belong to the addressed service/store. */
    public static final String FLASH_PRICE_NOT_FOUND = "FLASH_PRICE_NOT_FOUND";

    /** State-machine violation, including a PAUSED or out-of-window reserve. */
    public static final String FLASH_PRICE_STATE_NOT_ALLOWED = "FLASH_PRICE_STATE_NOT_ALLOWED";

    /** Another active window (PENDING_REVIEW/SCHEDULED/ACTIVE/PAUSED) overlaps this one. */
    public static final String FLASH_PRICE_WINDOW_OVERLAP = "FLASH_PRICE_WINDOW_OVERLAP";

    /** totalQuota sold out. */
    public static final String FLASH_PRICE_QUOTA_EXCEEDED = "FLASH_PRICE_QUOTA_EXCEEDED";

    /** perUserLimit reached (RESERVED/CONSUMED counted). */
    public static final String FLASH_PRICE_LIMIT_REACHED = "FLASH_PRICE_LIMIT_REACHED";

    /** Discount/price constraint failed (e.g. flashPrice >= current service price at approve). */
    public static final String FLASH_PRICE_INVALID = "FLASH_PRICE_INVALID";

    /** The switch is off while an internal reserve was attempted (fail closed, 503). */
    public static final String FLASH_PRICE_DISABLED = "FLASH_PRICE_DISABLED";
}
