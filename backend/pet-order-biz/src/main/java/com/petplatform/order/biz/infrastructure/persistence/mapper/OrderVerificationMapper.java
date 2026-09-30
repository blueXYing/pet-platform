package com.petplatform.order.biz.infrastructure.persistence.mapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface OrderVerificationMapper {
 Row row(@Param("id") long id); Commit committed(@Param("id") long id);
 int active(@Param("id") long id);int acquire(Map<String,Object> v);int guardStatus(Map<String,Object> v);
 int complete(Map<String,Object> v);int record(Map<String,Object> v);int log(Map<String,Object> v);
 final class Row {public Long id,storeId,merchantId,currentAftersaleId,version;public String orderStage,verificationStatus,aftersaleStatus;public LocalDateTime verifiedAt,completedAt;}
 final class Commit {public Long orderId,storeId,verificationId,credentialId,attemptId,commandId,operatorId,orderVersion,eventId,aftersaleId;public String requestId,aftersaleStatus;public LocalDateTime verifiedAt;}
}
