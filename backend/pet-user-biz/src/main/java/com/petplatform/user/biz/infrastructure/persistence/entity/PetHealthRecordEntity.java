package com.petplatform.user.biz.infrastructure.persistence.entity;

import java.time.LocalDate;

/** user_pet_health_record row (SQL68); plain getter/setter bean for the MyBatis result mapping. */
public class PetHealthRecordEntity {
    private Long id;
    private Long userId;
    private Long petId;
    private String recordType;
    private LocalDate recordDate;
    private String vaccineName;
    private Integer doseNumber;
    private String dewormingType;
    private String productName;
    private LocalDate nextRecordDate;
    private String note;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPetId() { return petId; }
    public void setPetId(Long petId) { this.petId = petId; }
    public String getRecordType() { return recordType; }
    public void setRecordType(String recordType) { this.recordType = recordType; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }
    public String getVaccineName() { return vaccineName; }
    public void setVaccineName(String vaccineName) { this.vaccineName = vaccineName; }
    public Integer getDoseNumber() { return doseNumber; }
    public void setDoseNumber(Integer doseNumber) { this.doseNumber = doseNumber; }
    public String getDewormingType() { return dewormingType; }
    public void setDewormingType(String dewormingType) { this.dewormingType = dewormingType; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public LocalDate getNextRecordDate() { return nextRecordDate; }
    public void setNextRecordDate(LocalDate nextRecordDate) { this.nextRecordDate = nextRecordDate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
