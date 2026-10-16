package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Contract-63 v0.2 ORDER-owned disposal SQL: abnormal-close CAS, audit row and status log. */
public interface OrderAdminDispositionMapper {

    String locateStore(@Param("orderId") long orderId);

    int closeAbnormal(@Param("orderId") long orderId, @Param("refundId") long refundId,
            @Param("canceledAt") LocalDateTime canceledAt, @Param("now") LocalDateTime now,
            @Param("version") long version);

    int insertAction(@Param("id") long id, @Param("orderId") long orderId, @Param("storeId") long storeId,
            @Param("reasonCode") String reasonCode, @Param("reason") String reason,
            @Param("orderVersionBefore") long orderVersionBefore, @Param("refundId") long refundId,
            @Param("operatorId") long operatorId, @Param("actedAt") LocalDateTime actedAt,
            @Param("authzVersion") String authzVersion, @Param("requestId") String requestId,
            @Param("traceId") String traceId, @Param("now") LocalDateTime now);

    List<ActionRow> selectByRequest(@Param("requestId") String requestId);

    List<ActionRow> selectByOrder(@Param("orderId") long orderId);

    int insertLog(@Param("id") long id, @Param("orderId") long orderId, @Param("fromStage") String fromStage,
            @Param("operatorId") long operatorId, @Param("requestId") String requestId,
            @Param("remark") String remark, @Param("now") LocalDateTime now);

    int projectRefund(@Param("orderId") long orderId, @Param("refundId") long refundId,
            @Param("amount") java.math.BigDecimal amount);

    class ActionRow {
        public long id;
        public long orderId;
        public long storeId;
        public String actionType;
        public String reasonCode;
        public String reason;
        public long orderVersionBefore;
        public Long refundOrderId;
        public long actedByOperatorId;
        public LocalDateTime actedAt;
        public String authzVersion;
        public String requestId;
        public String traceId;
    }
}
