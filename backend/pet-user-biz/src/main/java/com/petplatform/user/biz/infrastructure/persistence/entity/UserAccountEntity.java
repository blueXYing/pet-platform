package com.petplatform.user.biz.infrastructure.persistence.entity;

/** user_account row (Schema 06 §1); columns not selected by a statement stay null. */
public class UserAccountEntity {
    private Long id;
    private String phone;
    private String nickname;
    private String avatarUrl;
    private Boolean passwordEnabled;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public Boolean getPasswordEnabled() { return passwordEnabled; }
    public void setPasswordEnabled(Boolean passwordEnabled) { this.passwordEnabled = passwordEnabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
