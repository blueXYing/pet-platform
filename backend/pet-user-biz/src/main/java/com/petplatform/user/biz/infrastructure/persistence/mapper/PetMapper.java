package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.PetEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** user_pet plus the user_account guard read; SQL lives in resources/mapper/PetMapper.xml. */
public interface PetMapper {

    UserAccountEntity selectUserById(@Param("userId") long userId);

    PetEntity selectPetById(@Param("petId") long petId);

    PetEntity selectPetByIdForUpdate(@Param("petId") long petId);

    List<PetEntity> selectActivePetsByOwner(@Param("ownerUserId") long ownerUserId);

    int insertPet(PetEntity pet);

    int updatePetFields(PetEntity pet);

    int softDeletePet(@Param("petId") long petId, @Param("ownerUserId") long ownerUserId);

    int clearDefaultExcept(@Param("ownerUserId") long ownerUserId, @Param("keepPetId") long keepPetId);
}
