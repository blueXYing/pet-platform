package com.petplatform.user.biz.apiimpl;

import com.petplatform.user.api.dto.UserStatus;
import com.petplatform.user.api.query.UserPhoneVerificationApi;
import com.petplatform.user.api.query.UserIdQuery;
import com.petplatform.user.biz.application.PetService;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Contract 54 §1: the confirm channel compares the staff invitation phone against the account's
 * verified login phone. The phone is only ever set through the S9 WeChat getPhoneNumber exchange
 * (UserAuthService), so equality here is equality of verified facts. Fail closed: missing
 * account, non-ACTIVE account or a missing phone fact is a mismatch, never an error disclosure.
 */
public final class UserPhoneVerificationApiImpl implements UserPhoneVerificationApi {
    private static final Pattern PHONE = Pattern.compile("1[0-9]{10}");
    private final UserAuthStore store;

    public UserPhoneVerificationApiImpl(DataSource dataSource) {
        this.store = new UserAuthStore(dataSource);
    }

    @Override
    public boolean hasVerifiedPhone(UserIdQuery query, String phone) {
        Objects.requireNonNull(query, "query is required");
        if (phone == null || !PHONE.matcher(phone).matches()) return false;
        long userId = PetService.numericId(query.userId(), "userId");
        UserAuthStore.AccountRow account;
        try {
            account = store.findAccount(userId, false);
        } catch (RuntimeException failure) {
            return false;
        }
        if (account == null || !UserStatus.ACTIVE.name().equals(account.status())) return false;
        return account.phone() != null && account.phone().equals(phone);
    }

    @Override
    public Set<String> verifiedPhonesEqualTo(UserIdQuery query, Collection<String> phones) {
        // Contract 54 §7: one account read, batch equality, fail closed on any unreadable fact.
        Objects.requireNonNull(query, "query is required");
        if (phones == null || phones.isEmpty()) return Set.of();
        long userId = PetService.numericId(query.userId(), "userId");
        UserAuthStore.AccountRow account;
        try {
            account = store.findAccount(userId, false);
        } catch (RuntimeException failure) {
            return Set.of();
        }
        if (account == null || !UserStatus.ACTIVE.name().equals(account.status())
                || account.phone() == null) {
            return Set.of();
        }
        Set<String> matched = new HashSet<>();
        for (String phone : phones) {
            if (phone != null && PHONE.matcher(phone).matches()
                    && account.phone().equals(phone)) {
                matched.add(phone);
            }
        }
        return Set.copyOf(matched);
    }
}
