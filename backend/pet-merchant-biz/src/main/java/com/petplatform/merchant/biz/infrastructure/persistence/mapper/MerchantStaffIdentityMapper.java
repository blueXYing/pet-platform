package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffIdentityScopeEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffMembershipRowEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Read-only member/grant/action resolution; writers belong to the approved binding slice. */
public interface MerchantStaffIdentityMapper {

    MerchantStaffIdentityScopeEntity selectIdentityScope(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("userId") long userId);

    MerchantStaffIdentityScopeEntity lockIdentityScope(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("userId") long userId);

    List<String> listActionCodes(@Param("memberId") long memberId, @Param("storeId") long storeId);

    List<String> listActionCodesLocked(@Param("memberId") long memberId, @Param("storeId") long storeId);

    List<MerchantStaffMembershipRowEntity> listStaffMembershipRows(@Param("userId") long userId,
            @Param("limit") int limit, @Param("offset") int offset);

    long countStaffMembershipRows(@Param("userId") long userId);
}
