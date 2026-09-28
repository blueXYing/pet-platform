package com.petplatform.order.biz.infrastructure.persistence.mapper;

import com.petplatform.order.biz.infrastructure.persistence.entity.OrderCreationBinding;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** ORDER-owned SQL only. Every statement joins the caller's DataSource transaction. */
public interface OrderCreationMapper {
    void setUtcTimeZone();
    void setLockWaitTimeout();
    int insertBinding(Map<String, Object> values);
    OrderCreationBinding selectBindingForUpdate(@Param("requestKey") byte[] requestKey);
    int succeedBinding(@Param("requestKey") byte[] requestKey,
                       @Param("receiptJson") String receiptJson);
    Long selectOrderOwner(@Param("orderId") long orderId);
    int insertOrder(Map<String, Object> values);
    int insertServiceSnapshot(Map<String, Object> values);
    int insertPetSnapshot(Map<String, Object> values);
    int insertInputSnapshot(Map<String, Object> values);
    int insertStatusLog(Map<String, Object> values);
    int insertCreationAudit(Map<String, Object> values);
}
