package com.petplatform.user.biz.infrastructure.persistence.entity;

/** user_address row (SQL55); plain getter/setter bean for the MyBatis result mapping. */
public class AddressEntity {
    private Long id;
    private Long userId;
    private String contactName;
    private String contactPhone;
    private String region;
    private String detail;
    private Boolean isDefault;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
