package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.RequestBinding;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.Route;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** All payment center SQL is in PaymentCenterMapper.xml. */
public interface PaymentCenterMapper {
    CenterRow selectCenterById(@Param("paymentId") long paymentId);
    CenterRow selectCenterByIdForUpdate(@Param("paymentId") long paymentId);
    CenterRow selectCenterByNo(@Param("paymentNo") long paymentNo);
    CenterRow selectCenterByBiz(@Param("bizType") String bizType, @Param("bizNo") String bizNo);
    CenterRow selectCenterByBizForUpdate(@Param("bizType") String bizType, @Param("bizNo") String bizNo);
    Route selectRouteByNo(@Param("paymentNo") long paymentNo);
    int insertCenterPayment(Map<String, Object> p);

    int insertRequest(Map<String, Object> p);
    RequestBinding selectRequestForUpdate(@Param("key") byte[] key);
    int bindRequest(@Param("paymentId") long paymentId, @Param("key") byte[] key);
}
