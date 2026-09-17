package com.petplatform.user.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** user_pet row; birth_date maps through LocalDate exactly as before the migration. */
public class PetEntity {
    private Long id;
    private Long userId;
    private String name;
    private String petType;
    private String breedName;
    private LocalDate birthDate;
    private String sex;
    private BigDecimal weightKg;
    private String sterilizationStatus;
    private String vaccineStatus;
    private String healthNote;
    private String avatarUrl;
    private Boolean isDefault;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPetType() { return petType; }
    public void setPetType(String petType) { this.petType = petType; }
    public String getBreedName() { return breedName; }
    public void setBreedName(String breedName) { this.breedName = breedName; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
    public String getSterilizationStatus() { return sterilizationStatus; }
    public void setSterilizationStatus(String sterilizationStatus) { this.sterilizationStatus = sterilizationStatus; }
    public String getVaccineStatus() { return vaccineStatus; }
    public void setVaccineStatus(String vaccineStatus) { this.vaccineStatus = vaccineStatus; }
    public String getHealthNote() { return healthNote; }
    public void setHealthNote(String healthNote) { this.healthNote = healthNote; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
