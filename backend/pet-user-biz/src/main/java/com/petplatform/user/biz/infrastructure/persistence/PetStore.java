package com.petplatform.user.biz.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only this Owner's tables (user_account, user_pet). SQL errors never escape with bound values. */
public final class PetStore {
    public record UserRow(long id, String phone, String nickname, String status) {}

    public record PetRow(long id, long userId, String name, String petType, String breedName,
                         LocalDate birthDate, String sex, BigDecimal weightKg,
                         String sterilizationStatus, String vaccineStatus, String healthNote,
                         String avatarUrl, boolean isDefault, String status) {}

    private final JdbcTemplate jdbc;

    public PetStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
    }

    public UserRow findUser(long userId) {
        return jdbc.query("SELECT id,phone,nickname,status FROM user_account WHERE id=?",
                (rs, row) -> new UserRow(rs.getLong("id"), rs.getString("phone"),
                        rs.getString("nickname"), rs.getString("status")), userId)
                .stream().findFirst().orElse(null);
    }

    public PetRow findPet(long petId, boolean forUpdate) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
                       sterilization_status,vaccine_status,health_note,avatar_url,is_default,status
                FROM user_pet WHERE id=?""" + suffix,
                PetStore::mapPet, petId).stream().findFirst().orElse(null);
    }

    public List<PetRow> listActivePets(long ownerUserId) {
        return jdbc.query("""
                SELECT id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
                       sterilization_status,vaccine_status,health_note,avatar_url,is_default,status
                FROM user_pet WHERE user_id=? AND status='ACTIVE'
                ORDER BY created_at DESC, id DESC
                """, PetStore::mapPet, ownerUserId);
    }

    public void insertPet(PetRow pet) {
        int changed = jdbc.update("""
                INSERT INTO user_pet
                (id,user_id,name,pet_type,breed_name,birth_date,sex,weight_kg,
                 sterilization_status,vaccine_status,health_note,avatar_url,is_default,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,NOW(3),NOW(3))
                """, pet.id(), pet.userId(), pet.name(), pet.petType(), pet.breedName(),
                pet.birthDate() == null ? null : Date.valueOf(pet.birthDate()), pet.sex(), pet.weightKg(),
                pet.sterilizationStatus(), pet.vaccineStatus(), pet.healthNote(), pet.avatarUrl(),
                pet.isDefault());
        if (changed != 1) throw new IllegalStateException("Pet insert failed; transaction rolled back");
    }

    public void updatePetFields(PetRow pet) {
        int changed = jdbc.update("""
                UPDATE user_pet SET name=?,breed_name=?,birth_date=?,sex=?,weight_kg=?,
                sterilization_status=?,vaccine_status=?,health_note=?,avatar_url=?,is_default=?,
                version=version+1,updated_at=NOW(3)
                WHERE id=? AND user_id=? AND status='ACTIVE'
                """, pet.name(), pet.breedName(), pet.birthDate() == null ? null : Date.valueOf(pet.birthDate()),
                pet.sex(), pet.weightKg(), pet.sterilizationStatus(), pet.vaccineStatus(),
                pet.healthNote(), pet.avatarUrl(), pet.isDefault(), pet.id(), pet.userId());
        if (changed != 1) throw new IllegalStateException("Pet update failed; transaction rolled back");
    }

    public void softDelete(long petId, long ownerUserId) {
        int changed = jdbc.update("""
                UPDATE user_pet SET status='DISABLED',is_default=0,version=version+1,updated_at=NOW(3)
                WHERE id=? AND user_id=? AND status='ACTIVE'
                """, petId, ownerUserId);
        if (changed != 1) throw new IllegalStateException("Pet delete failed; transaction rolled back");
    }

    public void clearDefault(long ownerUserId, long keepPetId) {
        jdbc.update("UPDATE user_pet SET is_default=0,updated_at=NOW(3) WHERE user_id=? AND is_default=1 AND id<>?",
                ownerUserId, keepPetId);
    }

    private static PetRow mapPet(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp birth = rs.getTimestamp("birth_date");
        return new PetRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                rs.getString("pet_type"), rs.getString("breed_name"),
                birth == null ? null : birth.toLocalDateTime().toLocalDate(),
                rs.getString("sex"), rs.getBigDecimal("weight_kg"),
                rs.getString("sterilization_status"), rs.getString("vaccine_status"),
                rs.getString("health_note"), rs.getString("avatar_url"),
                rs.getInt("is_default") != 0, rs.getString("status"));
    }
}
