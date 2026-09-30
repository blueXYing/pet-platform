package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentRefundStore.Dispatch;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentRefundStore.Receipt;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** PAYMENT refund SQL is defined in PaymentRefundMapper.xml. */
public interface PaymentRefundMapper {
    void setUtcTimeZone();
    void setLockWaitTimeout();
    LocalDateTime utcNow();
    Dispatch selectByRefund(@Param("refundOrderId") long refundOrderId);
    Dispatch selectByRefundForUpdate(@Param("refundOrderId") long refundOrderId);
    int insertDispatch(Map<String, Object> p);
    int insertFunding(Map<String,Object> p);
    Funding funding(@Param("refund")long refund);
    class Funding {public Long refundOrderId;public String committedEvidenceId,checkJson,evidenceJson,requestSha256;public LocalDateTime createdAt;}
    Long countReceipt(@Param("refundOrderId") long refundOrderId, @Param("sha") String sha);
    int insertReceipt(Map<String, Object> p);
    int markPending(Map<String, Object> p);
    int markSuccess(Map<String, Object> p);
    int markReconciliation(Map<String, Object> p);
    Receipt selectTerminalReceipt(@Param("refundOrderId") long refundOrderId, @Param("sha") String sha);
}
