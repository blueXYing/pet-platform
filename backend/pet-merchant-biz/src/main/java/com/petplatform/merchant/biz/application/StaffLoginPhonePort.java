package com.petplatform.merchant.biz.application;

/**
 * Contract 54 §1 confirm-channel port: compares an invitation phone against the session user's
 * verified login phone (the S9 WeChat getPhoneNumber account fact). Implementations must fail
 * closed; the raw phone never travels back from the user module.
 */
@FunctionalInterface
public interface StaffLoginPhonePort {
    boolean matchesSessionUserPhone(long userId, String phone);
}
