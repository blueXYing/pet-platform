package com.petplatform.admin.api.dto;

import java.time.OffsetDateTime;

/** All fields are read in one Owner transaction. No token is retained in this DTO. */
public record AdminSessionView(
    AdminSessionPrincipal principal,
    OffsetDateTime expiresAt,
    AdminPermissionSnapshot permissions) {}
