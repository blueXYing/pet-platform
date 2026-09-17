package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.entity.PetEntity;
import com.petplatform.user.biz.infrastructure.persistence.entity.UserAccountEntity;
import com.petplatform.user.biz.infrastructure.persistence.mapper.PetMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** Only this Owner's tables (user_account, user_pet). SQL errors never escape with bound values. */
public final class PetStore {
    public record UserRow(long id, String phone, String nickname, String status) {}

    public record PetRow(long id, long userId, String name, String petType, String breedName,
                         LocalDate birthDate, String sex, BigDecimal weightKg,
                         String sterilizationStatus, String vaccineStatus, String healthNote,
                         String avatarUrl, boolean isDefault, String status) {}

    private final SqlSessionTemplate template;

    public PetStore(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    public UserRow findUser(long userId) {
        UserAccountEntity row = pets().selectUserById(userId);
        return row == null ? null
                : new UserRow(row.getId(), row.getPhone(), row.getNickname(), row.getStatus());
    }

    public PetRow findPet(long petId, boolean forUpdate) {
        PetEntity row = forUpdate ? pets().selectPetByIdForUpdate(petId) : pets().selectPetById(petId);
        return row == null ? null : toRow(row);
    }

    public List<PetRow> listActivePets(long ownerUserId) {
        return pets().selectActivePetsByOwner(ownerUserId).stream().map(PetStore::toRow).toList();
    }

    public void insertPet(PetRow pet) {
        int changed = pets().insertPet(toEntity(pet));
        if (changed != 1) throw new IllegalStateException("Pet insert failed; transaction rolled back");
    }

    public void updatePetFields(PetRow pet) {
        int changed = pets().updatePetFields(toEntity(pet));
        if (changed != 1) throw new IllegalStateException("Pet update failed; transaction rolled back");
    }

    public void softDelete(long petId, long ownerUserId) {
        int changed = pets().softDeletePet(petId, ownerUserId);
        if (changed != 1) throw new IllegalStateException("Pet delete failed; transaction rolled back");
    }

    public void clearDefault(long ownerUserId, long keepPetId) {
        pets().clearDefaultExcept(ownerUserId, keepPetId);
    }

    private PetMapper pets() {
        return template.getMapper(PetMapper.class);
    }

    private static PetRow toRow(PetEntity row) {
        return new PetRow(row.getId(), row.getUserId(), row.getName(), row.getPetType(),
                row.getBreedName(), row.getBirthDate(), row.getSex(), row.getWeightKg(),
                row.getSterilizationStatus(), row.getVaccineStatus(), row.getHealthNote(),
                row.getAvatarUrl(), Boolean.TRUE.equals(row.getIsDefault()), row.getStatus());
    }

    private static PetEntity toEntity(PetRow pet) {
        PetEntity entity = new PetEntity();
        entity.setId(pet.id());
        entity.setUserId(pet.userId());
        entity.setName(pet.name());
        entity.setPetType(pet.petType());
        entity.setBreedName(pet.breedName());
        entity.setBirthDate(pet.birthDate());
        entity.setSex(pet.sex());
        entity.setWeightKg(pet.weightKg());
        entity.setSterilizationStatus(pet.sterilizationStatus());
        entity.setVaccineStatus(pet.vaccineStatus());
        entity.setHealthNote(pet.healthNote());
        entity.setAvatarUrl(pet.avatarUrl());
        entity.setIsDefault(pet.isDefault());
        entity.setStatus(pet.status());
        return entity;
    }
}
