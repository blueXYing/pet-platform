package com.petplatform.user.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.dto.FavoriteItemDTO;
import java.time.OffsetDateTime;

/** Contract 58 favorite read surface (session subject only; client never selects the owner). */
public interface FavoriteQueryApi {

    PageResult<FavoriteItemDTO> listMyFavorites(MyFavoriteListQuery query);

    /** Not favorited reads as favoriteId=null (200), never 404 — this is a state probe. */
    FavoriteStatus favoriteStatus(FavoriteStatusQuery query);

    /** targetType filter is optional: null means both SERVICE and STORE rows. */
    record MyFavoriteListQuery(String targetType, int page, int pageSize, QueryContext context) {}

    record FavoriteStatusQuery(String targetType, String targetId, QueryContext context) {}

    record FavoriteStatus(String favoriteId, OffsetDateTime favoritedAt) {}
}
