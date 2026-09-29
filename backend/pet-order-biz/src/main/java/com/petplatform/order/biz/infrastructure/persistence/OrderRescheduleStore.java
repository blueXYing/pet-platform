package com.petplatform.order.biz.infrastructure.persistence;
import javax.sql.DataSource;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderRescheduleMapper;
public final class OrderRescheduleStore {
 private final OrderRescheduleMapper mapper;
 public OrderRescheduleStore(DataSource source){mapper=OrderMybatis.template(source).getMapper(OrderRescheduleMapper.class);}
 public OrderRescheduleMapper mapper(){return mapper;}
}
