package com.petplatform.order.biz.infrastructure.persistence;

import javax.sql.DataSource;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderRefundApplicationMapper;

public final class OrderRefundApplicationStore {
    private final OrderRefundApplicationMapper mapper;
    public OrderRefundApplicationStore(DataSource source) {
        mapper=OrderMybatis.template(source).getMapper(OrderRefundApplicationMapper.class);
    }
    public OrderRefundApplicationMapper mapper() { return mapper; }
}
