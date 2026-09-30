package com.petplatform.order.biz.infrastructure.persistence;
import javax.sql.DataSource;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderCredentialMapper;
public final class OrderCredentialStore {
 private final OrderCredentialMapper mapper;
 public OrderCredentialStore(DataSource source){mapper=OrderMybatis.template(source).getMapper(OrderCredentialMapper.class);}
 public OrderCredentialMapper.Location locate(long id){return mapper.locate(id);}
}
