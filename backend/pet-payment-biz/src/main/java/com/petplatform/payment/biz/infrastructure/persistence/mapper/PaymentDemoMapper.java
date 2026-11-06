package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentDemoStore.DemoRow;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** All DEMO business-owner SQL is in PaymentDemoMapper.xml. */
public interface PaymentDemoMapper {
    int insertDemo(Map<String, Object> p);
    DemoRow selectDemoByRequestForUpdate(@Param("key") byte[] key);
    DemoRow selectDemoByNoForUpdate(@Param("demoNo") long demoNo);
    int markDemoResult(Map<String, Object> p);
}
