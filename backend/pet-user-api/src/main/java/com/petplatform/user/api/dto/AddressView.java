package com.petplatform.user.api.dto;

/** Address book view per contract 59 (HTTP10 §3.2.2). Owner-scoped plaintext projection. */
public record AddressView(
        String addressId,
        String contactName,
        String contactPhone,
        String region,
        String detail,
        boolean isDefault,
        String status
) {}
