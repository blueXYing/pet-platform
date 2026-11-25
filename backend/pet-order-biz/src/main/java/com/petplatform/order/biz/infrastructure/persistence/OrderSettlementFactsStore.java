package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderSettlementFactsMapper;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderSettlementFactsMapper.SettlementOrderRow;
import javax.sql.DataSource;

/** ORDER-owned read-only store for settlement pricing and blocking facts. */
public final class OrderSettlementFactsStore {
    private final OrderSettlementFactsMapper mapper;

    public OrderSettlementFactsStore(DataSource source) {
        this.mapper = OrderMybatis.template(source).getMapper(OrderSettlementFactsMapper.class);
    }

    public SettlementOrderRow findByOrderId(long orderId) {
        return mapper.selectForSettlement(orderId);
    }
}
