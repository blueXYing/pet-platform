package com.petplatform.admin.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminPermissionSnapshot(
    String operatorId,
    String authzVersion,
    OffsetDateTime checkedAt,
    List<Role> roles,
    AdminDataScope dataScope,
    List<String> actionCodes) {
  public AdminPermissionSnapshot {
    roles = List.copyOf(roles);
    actionCodes = actionCodes.stream().distinct().sorted().toList();
  }

  public record Role(String roleId, String roleCode, String displayName) {}
}
