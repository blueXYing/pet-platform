package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface OrderRefundApplicationMapper {
    Current current(@Param("order") long order);
    Proof application(@Param("application") long application);
    Commit committed(@Param("order") long order);
    int insertApplication(Map<String,Object> values);
    int bind(Map<String,Object> values);
    int decideProof(Map<String,Object> values);
    int decideOrder(Map<String,Object> values);
    int acquire(Map<String,Object> values);
    int commitGuard(Map<String,Object> values);
    int createOrder(Map<String,Object> values);
    int insertCommit(Map<String,Object> values);
    int projectSuccess(Map<String,Object> values);
    int recordSuccess(Map<String,Object> values);
    int log(Map<String,Object> values);

    class Current {
        public Long currentRefundApplicationId,refundOrderId,version;
        public String refundApplicationStatus,aftersaleStatus;
        public LocalDateTime verifiedAt,completedAt;
    }
    class Proof {
        public Long applicationId,orderId,storeId,merchantId,userId,reservationId,paymentId,paymentSuccessEventId;
        public Long appliedOrderVersion,applicationVersion,decisionId;
        public String channelTradeNo,applicationStatus;
        public BigDecimal paidAmount;
        public LocalDateTime paidAt,createdAt,merchantDeadline,decidedAt;
    }
    class Commit {
        public Long orderId,applicationId,decisionId,storeId,refundOrderId,orderVersion,successEventId;
        public LocalDateTime createdAt,succeededAt;
    }
}
