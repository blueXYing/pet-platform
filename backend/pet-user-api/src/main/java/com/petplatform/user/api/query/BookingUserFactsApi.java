package com.petplatform.user.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.dto.PetSnapshotDTO;
public interface BookingUserFactsApi {
    void checkActor(String userId, QueryContext context);
    void requireCurrentActor(String userId, String storeId, QueryContext context);
    PetSnapshotDTO readCurrentPet(String userId, String petId, String storeId, QueryContext context);
}
