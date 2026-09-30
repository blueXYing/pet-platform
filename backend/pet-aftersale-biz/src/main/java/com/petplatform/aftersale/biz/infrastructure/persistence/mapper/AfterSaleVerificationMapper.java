package com.petplatform.aftersale.biz.infrastructure.persistence.mapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface AfterSaleVerificationMapper {
 Case current(@Param("id") long id);Case active(@Param("order") long order);Proof proof(@Param("order") long order);
 int invalidate(Map<String,Object> v);int log(Map<String,Object> v);int record(Map<String,Object> v);
 final class Case {public Long id,orderId,userId,merchantId,storeId,version;public Integer activeFlag;public String status,sourceStage;public LocalDateTime invalidatedAt;}
 final class Proof {public Long verificationId,orderId,storeId,aftersaleId,caseVersion;public String caseStatus;public Boolean invalidated;public LocalDateTime verifiedAt;}
}
