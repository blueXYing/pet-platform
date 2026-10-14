package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.AddressEntity;
import org.apache.ibatis.annotations.Param;

/** user_address only; SQL lives in resources/mapper/AddressMapper.xml (22号 MyBatis ruling). */
public interface AddressMapper {

    AddressEntity selectAddressById(@Param("addressId") long addressId);

    AddressEntity selectAddressByIdForUpdate(@Param("addressId") long addressId);

    java.util.List<AddressEntity> selectActiveAddressesByOwner(@Param("ownerUserId") long ownerUserId);

    long countActiveByOwner(@Param("ownerUserId") long ownerUserId);

    int insertAddress(AddressEntity address);

    int updateAddressFields(AddressEntity address);

    int softDeleteAddress(@Param("addressId") long addressId, @Param("ownerUserId") long ownerUserId);

    int markDefault(@Param("addressId") long addressId, @Param("ownerUserId") long ownerUserId);

    int clearDefaultExcept(@Param("ownerUserId") long ownerUserId, @Param("keepAddressId") long keepAddressId);
}
