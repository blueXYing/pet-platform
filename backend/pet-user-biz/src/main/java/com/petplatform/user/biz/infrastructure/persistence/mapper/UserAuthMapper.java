package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.UserAuthIdentityEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** user_auth_identity / user_account auth statements, SQL kept verbatim (Schema 06 §1). */
public interface UserAuthMapper {

    @Select("""
            SELECT id,user_id,app_id,open_id,union_id FROM user_auth_identity
            WHERE identity_type='WECHAT_MINI' AND app_id=#{appId} AND open_id=#{openId}
            """)
    UserAuthIdentityEntity selectIdentity(@Param("appId") String appId, @Param("openId") String openId);

    @Insert("""
            INSERT INTO user_auth_identity
            (id,user_id,identity_type,app_id,open_id,union_id,created_at,updated_at)
            VALUES (#{identityId},#{userId},'WECHAT_MINI',#{appId},#{openId},#{unionId},NOW(3),NOW(3))
            """)
    int insertIdentity(@Param("identityId") long identityId, @Param("userId") long userId,
                       @Param("appId") String appId, @Param("openId") String openId,
                       @Param("unionId") String unionId);

    @Insert("""
            INSERT INTO user_account (id,phone,nickname,avatar_url,password_hash,password_enabled,status,created_at,updated_at)
            VALUES (#{userId},NULL,NULL,NULL,NULL,0,'ACTIVE',NOW(3),NOW(3))
            """)
    int insertAccount(@Param("userId") long userId);

    @Select("""
            SELECT id,phone,nickname,avatar_url,password_enabled,status FROM user_account
            WHERE id=#{userId}
            """)
    UserAccountEntity selectAccountById(@Param("userId") long userId);

    @Select("""
            SELECT id,phone,nickname,avatar_url,password_enabled,status FROM user_account
            WHERE id=#{userId} FOR UPDATE
            """)
    UserAccountEntity selectAccountByIdForUpdate(@Param("userId") long userId);

    @Select("SELECT id FROM user_account WHERE phone=#{phone}")
    Long selectAccountIdByPhone(@Param("phone") String phone);

    @Update("UPDATE user_account SET phone=#{phone},updated_at=NOW(3) WHERE id=#{userId}")
    int updatePhone(@Param("userId") long userId, @Param("phone") String phone);

    @Update("UPDATE user_account SET last_login_at=NOW(3),updated_at=NOW(3) WHERE id=#{userId}")
    int touchLogin(@Param("userId") long userId);

    @Update("""
            UPDATE user_account SET nickname=COALESCE(#{nickname},nickname),avatar_url=COALESCE(#{avatarUrl},avatar_url),
                   version=version+1,updated_at=NOW(3) WHERE id=#{userId}
            """)
    int updateProfile(@Param("userId") long userId, @Param("nickname") String nickname,
                      @Param("avatarUrl") String avatarUrl);
}
