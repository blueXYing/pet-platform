package com.petplatform.user.api.query;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.user.api.dto.PetSnapshotDTO;
import com.petplatform.user.api.dto.UserBasicDTO;
import java.util.List;

/** Internal API 07 §3.1. PET_NOT_FOUND semantics per CCR-W2-API-001 user domain 0.1. */
public interface UserQueryApi {

    UserBasicDTO getUser(UserIdQuery query);

    PetSnapshotDTO getPetSnapshot(PetSnapshotQuery query);

    boolean existsEnabledUser(UserIdQuery query);

    /**
     * Batch basics for derived cross-domain projections (contract 69, PRD 5.12 minimal ruling):
     * masked phone + nickname per id, absent ids are simply missing from the result. Same
     * masking discipline as {@link #getUser}: the raw credential never leaves this module.
     * 1..200 canonical ids per call; anything else is rejected.
     */
    default List<UserBasicDTO> listUsers(UserBasicsQuery query) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "user basics batch provider required");
    }

    /** userIds are canonical decimal strings, 1..200 distinct values. */
    record UserBasicsQuery(List<String> userIds) {}
}
