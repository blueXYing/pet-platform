package com.petplatform.points.biz.infrastructure.persistence.mapper;

import com.petplatform.points.biz.infrastructure.persistence.entity.PointsLedgerRowEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** C-end points read statements; SQL lives in PointsQueryMapper.xml (CCR-C006 P1). */
public interface PointsQueryMapper {

    Long selectBalance(@Param("userId") long userId);

    long countLedger(@Param("userId") long userId);

    List<PointsLedgerRowEntity> selectLedgerPage(
            @Param("userId") long userId,
            @Param("limit") int limit,
            @Param("offset") int offset);
}
