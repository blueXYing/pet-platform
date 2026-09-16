package com.petplatform.user.biz.infrastructure.persistence.entity;

/** user_auth_identity row (Schema 06 §1, identity_type fixed to WECHAT_MINI). */
public class UserAuthIdentityEntity {
    private Long id;
    private Long userId;
    private String appId;
    private String openId;
    private String unionId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getOpenId() { return openId; }
    public void setOpenId(String openId) { this.openId = openId; }
    public String getUnionId() { return unionId; }
    public void setUnionId(String unionId) { this.unionId = unionId; }
}
