package com.petplatform.community.api.query;

import com.petplatform.community.api.dto.EncyclopediaBreedDetailView;
import com.petplatform.community.api.dto.EncyclopediaBreedPage;
import com.petplatform.community.api.dto.EncyclopediaCategoryView;
import java.util.List;

/**
 * Encyclopedia public read face (contract 62): anonymous-browse GET semantics (PRD "all
 * users browse", STR-D8 analog). Published-only visibility is enforced in the community
 * kernel; offline/missing details answer BREED_NOT_FOUND (404) indistinguishably.
 */
public interface EncyclopediaQueryApi {

    List<EncyclopediaCategoryView> listEnabledCategories();

    EncyclopediaBreedPage pageBreeds(EncyclopediaBreedPageQuery query);

    EncyclopediaBreedDetailView getBreed(String breedId);
}
