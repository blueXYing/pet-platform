package com.petplatform.order.biz.infrastructure.persistence;
import javax.sql.DataSource;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderVerificationMapper;
public final class OrderVerificationStore {
 private final OrderVerificationMapper mapper;
 public OrderVerificationStore(DataSource source){mapper=OrderMybatis.template(source).getMapper(OrderVerificationMapper.class);}
 public OrderVerificationMapper mapper(){return mapper;}
}
