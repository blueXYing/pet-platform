package com.petplatform.points.biz.infrastructure.persistence.mapper;

import com.petplatform.points.biz.infrastructure.persistence.entity.AdminPointsLedgerEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * points_ledger admin read face (contract 65 §5, PRD 6.5 积分流水).
 * SQL lives in resources/mapper/AdminPointsQueryMapper.xml (22号 MyBatis ruling).
 */
public interface AdminPointsQueryMapper {

    List<AdminPointsLedgerEntity> selectLedger(
            @Param("userId") Long userId,
            @Param("bizType") String bizType,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countLedger(
            @Param("userId") Long userId,
            @Param("bizType") String bizType,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdTo") LocalDateTime createdTo);
}
