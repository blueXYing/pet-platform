package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.UserAuthIdentityEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.UserAuthMapper;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * Only this Owner's tables (user_account, user_auth_identity, Schema 06 §1). First-time WeChat
 * registration creates the account with a NULL phone and no random password; the phone is
 * filled in later by a real provider exchange. SQL errors never escape with bound values.
 */
public final class UserAuthStore {

    public record IdentityRow(long id, long userId, String appId, String openId, String unionId) {}

    public record AccountRow(
            long id, String phone, String nickname, String avatarUrl,
            boolean passwordEnabled, String status) {}

    private final SqlSessionTemplate template;

    public UserAuthStore(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    public IdentityRow findIdentity(String appId, String openId) {
        UserAuthIdentityEntity row = auth().selectIdentity(appId, openId);
        return row == null ? null : new IdentityRow(row.getId(), row.getUserId(),
                row.getAppId(), row.getOpenId(), row.getUnionId());
    }

    public void insertIdentity(long identityId, long userId, String appId, String openId, String unionId) {
        int changed = auth().insertIdentity(identityId, userId, appId, openId, unionId);
        if (changed != 1) throw new IllegalStateException("Identity insert failed; transaction rolled back");
    }

    public void insertAccount(long userId) {
        int changed = auth().insertAccount(userId);
        if (changed != 1) throw new IllegalStateException("Account insert failed; transaction rolled back");
    }

    public AccountRow findAccount(long userId, boolean forUpdate) {
        UserAccountEntity row = forUpdate
                ? auth().selectAccountByIdForUpdate(userId)
                : auth().selectAccountById(userId);
        return row == null ? null : toRow(row);
    }

    public Long findAccountIdByPhone(String phone) {
        return auth().selectAccountIdByPhone(phone);
    }

    public void setPhone(long userId, String phone) {
        int changed = auth().updatePhone(userId, phone);
        if (changed != 1) throw new IllegalStateException("Phone binding failed; transaction rolled back");
    }

    public void touchLogin(long userId) {
        auth().touchLogin(userId);
    }

    public void updateProfile(long userId, String nickname, String avatarUrl) {
        int changed = auth().updateProfile(userId, nickname, avatarUrl);
        if (changed != 1) throw new IllegalStateException("Profile update failed; transaction rolled back");
    }

    private UserAuthMapper auth() {
        return template.getMapper(UserAuthMapper.class);
    }

    private static AccountRow toRow(UserAccountEntity row) {
        return new AccountRow(row.getId(), row.getPhone(), row.getNickname(), row.getAvatarUrl(),
                Boolean.TRUE.equals(row.getPasswordEnabled()), row.getStatus());
    }
}
