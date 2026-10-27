package com.petplatform.user.biz.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** CCR-C-PET-AVATAR-001: internal asset refs are accepted only by the server-side validator. */
class PetValidatorAvatarTest {
  @Test
  void acceptsExternalHttpsAndOpaquePetAvatarRefs() {
    assertEquals("https://cdn.example.invalid/pet.png",
        PetValidator.avatar("https://cdn.example.invalid/pet.png"));
    assertEquals("asset:123456789", PetValidator.avatar("asset:123456789"));
    assertNull(PetValidator.avatar(null));
  }

  @Test
  void rejectsMalformedOrNonHttpsValues() {
    assertThrows(IllegalArgumentException.class, () -> PetValidator.avatar("http://cdn.example.invalid/pet.png"));
    assertThrows(IllegalArgumentException.class, () -> PetValidator.avatar("asset:0"));
    assertThrows(IllegalArgumentException.class, () -> PetValidator.avatar("asset:abc"));
    assertThrows(IllegalArgumentException.class, () -> PetValidator.avatar("https://" + "x".repeat(510)));
  }
}
