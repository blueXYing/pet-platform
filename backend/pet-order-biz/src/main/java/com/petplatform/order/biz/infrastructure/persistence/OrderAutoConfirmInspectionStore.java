package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmInspectionMapper;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;

public final class OrderAutoConfirmInspectionStore {
    private final OrderAutoConfirmInspectionMapper mapper;

    public OrderAutoConfirmInspectionStore(DataSource source) {
        mapper = OrderMybatis.template(source).getMapper(OrderAutoConfirmInspectionMapper.class);
    }

    public LocalDateTime databaseNow() { return mapper.databaseNow(); }
    public List<OrderAutoConfirmInspectionMapper.Row> scan(long afterOrderId, int limit) {
        return mapper.scan(afterOrderId, limit);
    }
}
