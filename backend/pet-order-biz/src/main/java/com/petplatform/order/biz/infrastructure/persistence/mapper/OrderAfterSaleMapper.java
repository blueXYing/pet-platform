package com.petplatform.order.biz.infrastructure.persistence.mapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface OrderAfterSaleMapper {
 Source source(@Param("caseId") long caseId);
 Commit committed(@Param("order") long order);
 int committedGuard(@Param("order")long order,@Param("token")String token);
 int insertSource(Map<String,Object> values); int bind(Map<String,Object> values);
 int transition(Map<String,Object> values); int transitionSource(Map<String,Object> values);
 int acquire(Map<String,Object> values); int commitGuard(Map<String,Object> values);
 int createRefund(Map<String,Object> values); int insertCommit(Map<String,Object> values);
 int projectSuccess(Map<String,Object> values); int success(Map<String,Object> values); int log(Map<String,Object> values);
 class Source {public Long caseId,orderId,storeId,caseVersion,orderVersion;public String status,sourceStage,sourceJson;public LocalDateTime createdAt;}
 class Commit {public Long orderId,storeId,caseId,decisionId,refundOrderId,orderVersion,successEventId;public String refundType,sourceStage,fundingEvidenceId,token;public BigDecimal refundAmount;public LocalDateTime createdAt,succeededAt;}
}
