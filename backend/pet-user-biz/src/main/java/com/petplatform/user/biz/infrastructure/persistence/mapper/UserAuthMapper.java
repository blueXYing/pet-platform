package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.UserAuthIdentityEntity;
import org.apache.ibatis.annotations.Param;

/** user_auth_identity / user_account auth statements; SQL lives in resources/mapper/UserAuthMapper.xml. */
public interface UserAuthMapper {

    UserAuthIdentityEntity selectIdentity(@Param("appId") String appId, @Param("openId") String openId);

    int insertIdentity(@Param("identityId") long identityId, @Param("userId") long userId,
                       @Param("appId") String appId, @Param("openId") String openId,
                       @Param("unionId") String unionId);

    int insertAccount(@Param("userId") long userId);

    UserAccountEntity selectAccountById(@Param("userId") long userId);

    UserAccountEntity selectAccountByIdForUpdate(@Param("userId") long userId);

    Long selectAccountIdByPhone(@Param("phone") String phone);

    int updatePhone(@Param("userId") long userId, @Param("phone") String phone);

    int touchLogin(@Param("userId") long userId);

    int updateProfile(@Param("userId") long userId, @Param("nickname") String nickname,
                      @Param("avatarUrl") String avatarUrl);
}
