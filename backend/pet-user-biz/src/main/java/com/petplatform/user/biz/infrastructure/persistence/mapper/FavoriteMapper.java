package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.FavoriteEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** user_favorite plus the user_account guard read; SQL lives in resources/mapper/FavoriteMapper.xml. */
public interface FavoriteMapper {

    String selectUserStatus(@Param("userId") long userId);

    FavoriteEntity selectFavorite(@Param("userId") long userId, @Param("targetType") String targetType,
            @Param("targetId") long targetId);

    FavoriteEntity selectFavoriteForUpdate(@Param("userId") long userId,
            @Param("targetType") String targetType, @Param("targetId") long targetId);

    int insertFavorite(FavoriteEntity favorite);

    int deleteFavorite(@Param("userId") long userId, @Param("targetType") String targetType,
            @Param("targetId") long targetId);

    long countFavorites(@Param("userId") long userId, @Param("targetType") String targetType);

    List<FavoriteEntity> selectFavoritePage(@Param("userId") long userId,
            @Param("targetType") String targetType, @Param("limit") int limit,
            @Param("offset") int offset);
}
