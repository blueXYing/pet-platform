package com.petplatform.order.biz.infrastructure.persistence.mapper;

import com.petplatform.order.biz.infrastructure.persistence.entity.OrderQueryViewEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only C-004 order query statements; SQL lives in OrderQueryMapper.xml. {@code filter} is
 * only ever one of the fixed OrderDisplayStatus predicates — request values never reach it.
 */
public interface OrderQueryMapper {

    long countMine(@Param("userId") long userId, @Param("filter") String filter);

    List<OrderQueryViewEntity> selectMinePage(@Param("userId") long userId,
            @Param("filter") String filter, @Param("limit") int limit, @Param("offset") long offset);

    OrderQueryViewEntity selectMineById(@Param("id") long id, @Param("userId") long userId);
}
