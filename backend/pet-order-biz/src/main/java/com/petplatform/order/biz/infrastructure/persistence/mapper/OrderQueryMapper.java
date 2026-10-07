package com.petplatform.order.biz.infrastructure.persistence.mapper;

import com.petplatform.order.biz.infrastructure.persistence.entity.OrderQueryViewEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only C-004 order query statements; SQL lives in OrderQueryMapper.xml. The optional
 * {@code displayStatus} selects one of the fixed XML filter branches (the service validates the
 * ten-value vocabulary first); everything else binds with {@code #{}}, never string
 * substitution.
 */
public interface OrderQueryMapper {

    long countMine(@Param("userId") long userId, @Param("displayStatus") String displayStatus);

    List<OrderQueryViewEntity> selectMinePage(@Param("userId") long userId,
            @Param("displayStatus") String displayStatus,
            @Param("limit") int limit, @Param("offset") long offset);

    OrderQueryViewEntity selectMineById(@Param("id") long id, @Param("userId") long userId);

    long countForStore(@Param("merchantId") long merchantId, @Param("storeId") long storeId,
            @Param("displayStatus") String displayStatus);

    List<OrderQueryViewEntity> selectForStorePage(@Param("merchantId") long merchantId,
            @Param("storeId") long storeId, @Param("displayStatus") String displayStatus,
            @Param("limit") int limit, @Param("offset") long offset);
}
