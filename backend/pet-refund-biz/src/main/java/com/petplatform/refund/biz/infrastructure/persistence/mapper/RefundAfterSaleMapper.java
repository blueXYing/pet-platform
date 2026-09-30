package com.petplatform.refund.biz.infrastructure.persistence.mapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface RefundAfterSaleMapper {
 int insertRefund(Map<String,Object> values);int insertExecution(Map<String,Object> values);int insertProof(Map<String,Object> values);
 Proof proof(@Param("refund")long refund);
 class Proof {public Long refundOrderId,caseId,decisionId,commandId,businessAftersaleId,businessApplicationId,initiatorId;public String fundingEvidenceId,proofJson,initiatorType;public LocalDateTime createdAt;}
}
