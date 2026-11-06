package com.petplatform.user.api.dto;

/**
 * Internal API 07 §3.1: masked phone only, never the raw credential.
 * avatarUrl is the user's own WeChat avatar external link (nullable, CCR-M-ORDER-CARD-001 /
 * CCR-M-REVIEW-VIEW-001 merchant read projections); consumers must degrade to a placeholder
 * when null and never treat it as trusted content.
 */
public record UserBasicDTO(
        String userId,
        String mobileMasked,
        String nickname,
        UserStatus status,
        String avatarUrl
) {}
