package com.petplatform.boot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * C-end (mini-program) auth assembly. Off by default: enabling it additionally requires an
 * authorized {@code WechatSessionProvider} bean — no real WeChat credentials exist in this
 * repository, so nothing can be enabled by accident.
 */
@ConfigurationProperties("pet.auth.c")
public class CAuthProperties {
    private boolean enabled;
    private String redisHost;
    private String redisUsername;
    private String redisPassword;
    private String cachePrefix = "auth001c:";
    private int redisPort = 6379;
    private int attemptTtlSeconds = 600;
    private int accessTtlSeconds = 900;
    private int grantWindowSeconds = 60;
    private int attemptCreatePerMinute = 30;
    private int attemptFailureLimit = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String v) { redisHost = v; }
    public String getRedisUsername() { return redisUsername; }
    public void setRedisUsername(String v) { redisUsername = v; }
    public String getRedisPassword() { return redisPassword; }
    public void setRedisPassword(String v) { redisPassword = v; }
    public String getCachePrefix() { return cachePrefix; }
    public void setCachePrefix(String v) { cachePrefix = v; }
    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int v) { redisPort = v; }
    public int getAttemptTtlSeconds() { return attemptTtlSeconds; }
    public void setAttemptTtlSeconds(int v) { attemptTtlSeconds = v; }
    public int getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(int v) { accessTtlSeconds = v; }
    public int getGrantWindowSeconds() { return grantWindowSeconds; }
    public void setGrantWindowSeconds(int v) { grantWindowSeconds = v; }
    public int getAttemptCreatePerMinute() { return attemptCreatePerMinute; }
    public void setAttemptCreatePerMinute(int v) { attemptCreatePerMinute = v; }
    public int getAttemptFailureLimit() { return attemptFailureLimit; }
    public void setAttemptFailureLimit(int v) { attemptFailureLimit = v; }

    @Override
    public String toString() {
        return "CAuthProperties[REDACTED]";
    }
}
