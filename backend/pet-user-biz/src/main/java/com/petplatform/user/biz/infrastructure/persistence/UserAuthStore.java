package com.petplatform.user.biz.infrastructure.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private final JdbcTemplate jdbc;

    public UserAuthStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
    }

    public IdentityRow findIdentity(String appId, String openId) {
        return jdbc.query("""
                SELECT id,user_id,app_id,open_id,union_id FROM user_auth_identity
                WHERE identity_type='WECHAT_MINI' AND app_id=? AND open_id=?
                """, (rs, row) -> new IdentityRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("app_id"), rs.getString("open_id"), rs.getString("union_id")),
                appId, openId).stream().findFirst().orElse(null);
    }

    public void insertIdentity(long identityId, long userId, String appId, String openId, String unionId) {
        int changed = jdbc.update("""
                INSERT INTO user_auth_identity
                (id,user_id,identity_type,app_id,open_id,union_id,created_at,updated_at)
                VALUES (?,?,'WECHAT_MINI',?,?,?,NOW(3),NOW(3))
                """, identityId, userId, appId, openId, unionId);
        if (changed != 1) throw new IllegalStateException("Identity insert failed; transaction rolled back");
    }

    public void insertAccount(long userId) {
        int changed = jdbc.update("""
                INSERT INTO user_account (id,phone,nickname,avatar_url,password_hash,password_enabled,status,created_at,updated_at)
                VALUES (?,NULL,NULL,NULL,NULL,0,'ACTIVE',NOW(3),NOW(3))
                """, userId);
        if (changed != 1) throw new IllegalStateException("Account insert failed; transaction rolled back");
    }

    public AccountRow findAccount(long userId, boolean forUpdate) {
        return jdbc.query("""
                SELECT id,phone,nickname,avatar_url,password_enabled,status FROM user_account
                WHERE id=?""" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> new AccountRow(rs.getLong("id"), rs.getString("phone"),
                        rs.getString("nickname"), rs.getString("avatar_url"),
                        rs.getInt("password_enabled") != 0, rs.getString("status")),
                userId).stream().findFirst().orElse(null);
    }

    public Long findAccountIdByPhone(String phone) {
        return jdbc.query("SELECT id FROM user_account WHERE phone=?",
                (rs, row) -> rs.getLong("id"), phone).stream().findFirst().orElse(null);
    }

    public void setPhone(long userId, String phone) {
        int changed = jdbc.update(
                "UPDATE user_account SET phone=?,updated_at=NOW(3) WHERE id=?", phone, userId);
        if (changed != 1) throw new IllegalStateException("Phone binding failed; transaction rolled back");
    }

    public void touchLogin(long userId) {
        jdbc.update("UPDATE user_account SET last_login_at=NOW(3),updated_at=NOW(3) WHERE id=?", userId);
    }

    public void updateProfile(long userId, String nickname, String avatarUrl) {
        int changed = jdbc.update("""
                UPDATE user_account SET nickname=COALESCE(?,nickname),avatar_url=COALESCE(?,avatar_url),
                       version=version+1,updated_at=NOW(3) WHERE id=?
                """, nickname, avatarUrl, userId);
        if (changed != 1) throw new IllegalStateException("Profile update failed; transaction rolled back");
    }
}
