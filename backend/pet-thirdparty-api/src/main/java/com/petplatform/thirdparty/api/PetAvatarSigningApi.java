package com.petplatform.thirdparty.api;

import java.time.Instant;

/**
 * CCR-C-PET-AVATAR-001: the only signing surface for consumer pet-avatar assets.
 * The implementation must re-check owner, purpose=PET_AVATAR, READY and the immutable
 * normalized object version; it must never sign an arbitrary assetId.
 */
public interface PetAvatarSigningApi {
  SignedPetAvatar signOwnedAvatar(String ownerUserId, String assetId);

  record SignedPetAvatar(String assetId, String signedUrl, Instant expiresAt) {}
}
