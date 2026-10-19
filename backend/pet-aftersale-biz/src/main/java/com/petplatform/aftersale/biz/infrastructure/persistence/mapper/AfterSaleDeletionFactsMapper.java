package com.petplatform.aftersale.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contract 66 deletion-blocking fact statements; SQL lives in AfterSaleDeletionFactsMapper.xml.
 * Open case = this domain's own active fact (active_flag=1, status in the PRD 处理中 set).
 */
public interface AfterSaleDeletionFactsMapper {

    long countOpenForUser(@Param("userId") long userId);

    long countOpenForStores(@Param("storeIds") List<Long> storeIds);
}
