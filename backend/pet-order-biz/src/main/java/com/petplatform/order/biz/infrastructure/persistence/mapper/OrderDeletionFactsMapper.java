package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contract 66 deletion-blocking fact statements; SQL lives in OrderDeletionFactsMapper.xml.
 * The blocking predicate is the fixed XML branch set of the five PRD-named DisplayOrderStatus
 * values (PENDING_PAYMENT / PENDING_CONFIRM / PENDING_SERVICE / REFUND_PENDING_CONFIRM /
 * REFUNDING) over this module's own pet_order projection columns — bound values only, never
 * string substitution (PERSISTENCE-XML gate).
 */
public interface OrderDeletionFactsMapper {

    long countBlockingForUser(@Param("userId") long userId);

    long countBlockingForStores(@Param("storeIds") List<Long> storeIds);
}
