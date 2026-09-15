package com.petplatform.user.api.dto;

/** Internal API 07 §3.1: masked phone only, never the raw credential. */
public record UserBasicDTO(
        String userId,
        String mobileMasked,
        String nickname,
        UserStatus status
) {}
