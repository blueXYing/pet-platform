package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.PetEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** user_pet plus the user_account guard read, SQL kept verbatim (PLAT-006). */
public interface PetMapper {

    @Select("SELECT id,phone,nickname,status FROM user_account WHERE id=#{userId}")
    UserAccountEntity selectUserById(@Param("userId") long userId);

    @Select("""
            SELECT id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
                   sterilization_status,vaccine_status,health_note,avatar_url,is_default,status
            FROM user_pet WHERE id=#{petId}
            """)
    PetEntity selectPetById(@Param("petId") long petId);

    @Select("""
            SELECT id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
                   sterilization_status,vaccine_status,health_note,avatar_url,is_default,status
            FROM user_pet WHERE id=#{petId} FOR UPDATE
            """)
    PetEntity selectPetByIdForUpdate(@Param("petId") long petId);

    @Select("""
            SELECT id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
                   sterilization_status,vaccine_status,health_note,avatar_url,is_default,status
            FROM user_pet WHERE user_id=#{ownerUserId} AND status='ACTIVE'
            ORDER BY created_at DESC, id DESC
            """)
    List<PetEntity> selectActivePetsByOwner(@Param("ownerUserId") long ownerUserId);

    @Insert("""
            INSERT INTO user_pet
            (id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
             sterilization_status,vaccine_status,health_note,avatar_url,is_default,status,version,created_at,updated_at)
            VALUES (#{id},#{userId},#{name},#{petType},#{breedName},#{birthDate},#{sex},#{weightKg},
             #{sterilizationStatus},#{vaccineStatus},#{healthNote},#{avatarUrl},#{isDefault},'ACTIVE',0,NOW(3),NOW(3))
            """)
    int insertPet(PetEntity pet);

    @Update("""
            UPDATE user_pet SET name=#{name},breed_name=#{breedName},birth_date=#{birthDate},sex=#{sex},weight_kg=#{weightKg},
            sterilization_status=#{sterilizationStatus},vaccine_status=#{vaccineStatus},health_note=#{healthNote},avatar_url=#{avatarUrl},is_default=#{isDefault},
            version=version+1,updated_at=NOW(3)
            WHERE id=#{id} AND user_id=#{userId} AND status='ACTIVE'
            """)
    int updatePetFields(PetEntity pet);

    @Update("""
            UPDATE user_pet SET status='DISABLED',is_default=0,version=version+1,updated_at=NOW(3)
            WHERE id=#{petId} AND user_id=#{ownerUserId} AND status='ACTIVE'
            """)
    int softDeletePet(@Param("petId") long petId, @Param("ownerUserId") long ownerUserId);

    @Update("UPDATE user_pet SET is_default=0,updated_at=NOW(3) WHERE user_id=#{ownerUserId} AND is_default=1 AND id<>#{keepPetId}")
    int clearDefaultExcept(@Param("ownerUserId") long ownerUserId, @Param("keepPetId") long keepPetId);
}
