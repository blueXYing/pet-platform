package com.petplatform.refund.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Contract-63 v0.2 disposal audit: append-only refund_admin_action rows (unique requestId). */
public interface AdminRefundDispositionMapper {

    String locateStore(@Param("refundOrderId") long refundOrderId);

    int insertAction(@Param("id") long id, @Param("refundOrderId") long refundOrderId,
            @Param("orderId") long orderId, @Param("actionType") String actionType,
            @Param("taskKey") String taskKey, @Param("taskState") String taskState,
            @Param("refundStatus") String refundStatus, @Param("operatorId") long operatorId,
            @Param("actedAt") LocalDateTime actedAt, @Param("authzVersion") String authzVersion,
            @Param("requestId") String requestId, @Param("traceId") String traceId,
            @Param("now") LocalDateTime now);

    List<ActionRow> selectByRequest(@Param("requestId") String requestId);

    class ActionRow {
        public long id;
        public long refundOrderId;
        public long orderId;
        public String actionType;
        public String taskKey;
        public String taskState;
        public String refundStatus;
        public long actedByOperatorId;
        public LocalDateTime actedAt;
        public String authzVersion;
        public String requestId;
        public String traceId;
    }
}
