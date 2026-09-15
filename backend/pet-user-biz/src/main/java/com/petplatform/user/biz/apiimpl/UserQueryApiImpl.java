package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.user.api.dto.PetSnapshotDTO;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.dto.UserStatus;
import com.petplatform.user.api.query.PetSnapshotQuery;
import com.petplatform.user.api.query.UserIdQuery;
import com.petplatform.user.api.query.UserQueryApi;
import com.petplatform.user.biz.application.PetService;
import com.petplatform.user.biz.infrastructure.persistence.PetStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Internal API 07 §3.1 with the user domain 0.1 semantics: owner-verified snapshots, masked phone. */
public final class UserQueryApiImpl implements UserQueryApi {
    private final PetStore store;

    public UserQueryApiImpl(DataSource dataSource) {
        this.store = new PetStore(dataSource);
    }

    @Override
    public UserBasicDTO getUser(UserIdQuery query) {
        Objects.requireNonNull(query, "query is required");
        PetStore.UserRow user = store.findUser(PetService.numericId(query.userId(), "userId"));
        if (user == null) return null;
        return new UserBasicDTO(Long.toUnsignedString(user.id()), mask(user.phone()), user.nickname(),
                UserStatus.valueOf(user.status()));
    }

    @Override
    public PetSnapshotDTO getPetSnapshot(PetSnapshotQuery query) {
        Objects.requireNonNull(query, "query is required");
        long owner = PetService.numericId(query.ownerUserId(), "ownerUserId");
        PetStore.PetRow pet = store.findPet(PetService.numericId(query.petId(), "petId"), false);
        if (pet == null || !"ACTIVE".equals(pet.status()) || pet.userId() != owner) {
            // Same anti-enumeration semantics as the pet reads.
            throw new ApiException("PET_NOT_FOUND", "宠物不存在");
        }
        return new PetSnapshotDTO(Long.toUnsignedString(pet.id()), Long.toUnsignedString(pet.userId()),
                pet.name(), pet.petType(), pet.breedName(), pet.sex(), pet.weightKg(), pet.healthNote());
    }

    @Override
    public boolean existsEnabledUser(UserIdQuery query) {
        PetStore.UserRow user = store.findUser(PetService.numericId(query.userId(), "userId"));
        return user != null && "ACTIVE".equals(user.status());
    }

    /** 138****1234: the raw credential never leaves this module. */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) return phone == null ? null : "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
