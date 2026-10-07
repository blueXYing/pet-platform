package com.petplatform.merchant.biz.application;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Contract 54 §1/§7 confirm-and-list channel port: compares invitation phones against the
 * session user's verified login phone (the S9 WeChat getPhoneNumber account fact). Implementations
 * must fail closed; the raw account phone never travels back from the user module.
 */
@FunctionalInterface
public interface StaffLoginPhonePort {
    boolean matchesSessionUserPhone(long userId, String phone);

    /**
     * Contract 54 §7 batch form for the employee invitation list: returns the subset of candidate
     * invitation phones equal to the session user's verified account phone. Default delegates to
     * the single comparison; the boot wiring routes it to the user module's one-read batch form.
     * The candidates are merchant-owned invitation values — never account credentials.
     */
    default Set<String> matchSessionUserPhones(long userId, Collection<String> phones) {
        if (phones == null || phones.isEmpty()) return Set.of();
        Set<String> matched = new HashSet<>();
        for (String phone : phones) {
            if (matchesSessionUserPhone(userId, phone)) matched.add(phone);
        }
        return Set.copyOf(matched);
    }
}
