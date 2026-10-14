package com.petplatform.user.biz.infrastructure.persistence.mapper;

import com.petplatform.user.biz.infrastructure.persistence.entity.FootprintEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** user_browse_footprint plus the user_account guard read; SQL in resources/mapper/FootprintMapper.xml. */
public interface FootprintMapper {

    String selectUserStatus(@Param("userId") long userId);

    FootprintEntity selectFootprint(@Param("userId") long userId, @Param("targetType") String targetType,
            @Param("targetId") long targetId);

    FootprintEntity selectFootprintById(@Param("footprintId") long footprintId,
            @Param("userId") long userId);

    int insertFootprint(FootprintEntity footprint);

    int touchFootprint(@Param("footprintId") long footprintId, @Param("userId") long userId);

    int deleteFootprint(@Param("footprintId") long footprintId, @Param("userId") long userId);

    long countFootprints(@Param("userId") long userId);

    List<FootprintEntity> selectFootprintPage(@Param("userId") long userId, @Param("limit") int limit,
            @Param("offset") int offset);
}
