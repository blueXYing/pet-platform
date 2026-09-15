package com.petplatform.user.api.query;

import com.petplatform.user.api.dto.PetSnapshotDTO;
import com.petplatform.user.api.dto.UserBasicDTO;

/** Internal API 07 §3.1. PET_NOT_FOUND semantics per CCR-W2-API-001 user domain 0.1. */
public interface UserQueryApi {

    UserBasicDTO getUser(UserIdQuery query);

    PetSnapshotDTO getPetSnapshot(PetSnapshotQuery query);

    boolean existsEnabledUser(UserIdQuery query);
}
