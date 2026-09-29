package com.petplatform.order.biz.infrastructure.persistence;
import javax.sql.DataSource;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderMerchantMapper;
/** Only ORDER application code receives this owner's mapper. */
public final class OrderMerchantStore {
    private final OrderMerchantMapper mapper;
    public OrderMerchantStore(DataSource source){mapper=OrderMybatis.template(source).getMapper(OrderMerchantMapper.class);}
    public OrderMerchantMapper mapper(){return mapper;}
}
