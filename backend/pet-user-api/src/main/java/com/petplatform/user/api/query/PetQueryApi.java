package com.petplatform.user.api.query;

import com.petplatform.user.api.dto.PetView;
import java.util.List;

/** Owner-scoped reads: the session user's ACTIVE pets only (CCR-W2-API-001 user domain 0.1). */
public interface PetQueryApi {

    List<PetView> listActivePets(String ownerUserId);

    PetView getPet(String petId, String ownerUserId);
}
