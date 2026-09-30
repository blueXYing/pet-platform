package com.petplatform.verification.biz.infrastructure.persistence.mapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface VerificationCompletionMapper {
 int attempt(Map<String,Object> v);int record(Map<String,Object> v);
 Result result(@Param("command") long command);int succeeded(@Param("id") long command);
 final class Result {public Long id,orderId,storeId,operatorId,operatorStaffId,commandId,verificationId,credentialId,orderVersion;public String operatorType,membershipKind,requestId,result,failReason,verifyMethod;public LocalDateTime createdAt,verifiedAt;}
}
