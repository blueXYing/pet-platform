package com.petplatform.boot.config;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pet.auth.admin")
public class AdminAuthProperties {
  private boolean enabled, migrationEnabled, maintenance;
  private String origin,
      redisHost,
      redisUsername,
      redisPassword,
      cachePrefix = "auth001:",
      keyId,
      macKeyBase64,
      encryptionKeyBase64,
      auditPath,
      migrationDatabase;
  private int redisPort = 6379, nodeId = -1;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public boolean isMigrationEnabled() {
    return migrationEnabled;
  }

  public void setMigrationEnabled(boolean v) {
    migrationEnabled = v;
  }

  public boolean isMaintenance() {
    return maintenance;
  }

  public void setMaintenance(boolean v) {
    maintenance = v;
  }

  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String v) {
    origin = v;
  }

  public String getRedisHost() {
    return redisHost;
  }

  public void setRedisHost(String v) {
    redisHost = v;
  }

  public int getRedisPort() {
    return redisPort;
  }

  public void setRedisPort(int v) {
    redisPort = v;
  }

  public String getRedisUsername() {
    return redisUsername;
  }

  public void setRedisUsername(String v) {
    redisUsername = v;
  }

  public String getRedisPassword() {
    return redisPassword;
  }

  public void setRedisPassword(String v) {
    redisPassword = v;
  }

  public String getCachePrefix() {
    return cachePrefix;
  }

  public void setCachePrefix(String v) {
    cachePrefix = v;
  }

  public String getKeyId() {
    return keyId;
  }

  public void setKeyId(String v) {
    keyId = v;
  }

  public String getMacKeyBase64() {
    return macKeyBase64;
  }

  public void setMacKeyBase64(String v) {
    macKeyBase64 = v;
  }

  public String getEncryptionKeyBase64() {
    return encryptionKeyBase64;
  }

  public void setEncryptionKeyBase64(String v) {
    encryptionKeyBase64 = v;
  }

  public String getAuditPath() {
    return auditPath;
  }

  public void setAuditPath(String v) {
    auditPath = v;
  }

  public String getMigrationDatabase() {
    return migrationDatabase;
  }

  public void setMigrationDatabase(String v) {
    migrationDatabase = v;
  }

  public int getNodeId() {
    return nodeId;
  }

  public void setNodeId(int v) {
    nodeId = v;
  }

  @Override
  public String toString() {
    return "AdminAuthProperties[REDACTED]";
  }
}
