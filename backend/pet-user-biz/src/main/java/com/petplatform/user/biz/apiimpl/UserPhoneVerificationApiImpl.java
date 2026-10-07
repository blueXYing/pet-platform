package com.petplatform.user.biz.apiimpl;

import com.petplatform.user.api.dto.UserStatus;
import com.petplatform.user.api.query.UserPhoneVerificationApi;
import com.petplatform.user.api.query.UserIdQuery;
import com.petplatform.user.biz.application.PetService;
import com.petplatform.user.biz.infrastructure.persistence.UserAuthStore;
import java.util.Objects;
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
    public String verifiedPhone(UserIdQuery query) {
        // Contract 54 §7 (user ruling 2026-10-07): purpose-bound session-phone read, one account
        // lookup, fail closed — any unreadable or absent fact reads as "no phone" (null).
        Objects.requireNonNull(query, "query is required");
        long userId = PetService.numericId(query.userId(), "userId");
        UserAuthStore.AccountRow account;
        try {
            account = store.findAccount(userId, false);
        } catch (RuntimeException failure) {
            return null;
        }
        if (account == null || !UserStatus.ACTIVE.name().equals(account.status())
                || account.phone() == null || !PHONE.matcher(account.phone()).matches()) {
            return null;
        }
        return account.phone();
    }
}
