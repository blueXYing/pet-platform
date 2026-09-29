package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;

public final class OrderAutoConfirmStore {
    private final OrderAutoConfirmMapper mapper;
    public OrderAutoConfirmStore(DataSource source) {
        mapper = OrderMybatis.template(source).getMapper(OrderAutoConfirmMapper.class);
    }
    public OrderAutoConfirmMapper.Row lock(long id) { return mapper.lock(id); }
    public List<String> proofs(long id, String requestId) { return mapper.proofs(id, requestId); }
    public int confirm(long id, long version, int round, LocalDateTime deadline, LocalDateTime now) {
        return mapper.confirm(id, version, round, deadline, now);
    }
    public void log(long id,long orderId,String from,String to,String type,String requestId,String remark) {
        if (mapper.log(id,orderId,from,to,type,requestId,remark) != 1)
            throw new IllegalStateException("Confirmation audit was not persisted");
    }
    public boolean hasAnomaly(long id,String requestId,String remark) {
        return mapper.anomalyCount(id,requestId,remark) > 0;
    }
    public List<Long> candidates(long after,int limit) { return mapper.candidates(after,limit); }
}
