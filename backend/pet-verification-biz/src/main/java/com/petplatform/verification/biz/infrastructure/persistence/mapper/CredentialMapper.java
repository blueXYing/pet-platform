package com.petplatform.verification.biz.infrastructure.persistence.mapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
public interface CredentialMapper {
 void utc();void timeout();LocalDateTime now();
 int reserve(Map<String,Object> v);Binding binding(Map<String,Object> v);int succeed(@Param("id") long id,@Param("result") byte[] result);
 State state(@Param("id") long id);int initialize(Map<String,Object> v);long historyCount(@Param("id") long id);long liveCount(@Param("id") long id);
 Long receiptCode(@Param("id") long commandId);Attempt receiptAttempt(@Param("id") long commandId);
 Code code(@Param("id") long id);int insertCode(Map<String,Object> v);int invalidate(Map<String,Object> v);int advance(Map<String,Object> v);
 int refresh(Map<String,Object> v);int refreshCount(@Param("order") long order,@Param("user") long user,@Param("since") LocalDateTime since);
 Fence fence(@Param("id") long order);int insertFence(Map<String,Object> v);
 int attempt(Map<String,Object> v);int failureCount(@Param("order") long order,@Param("since") LocalDateTime since);int riskLock(Map<String,Object> v);
 final class State {public Long orderId,storeId,reservationId,epoch,currentCredentialId,version;public LocalDateTime lockedUntil;}
 final class Code {public Long id,orderId,merchantId,storeId,reservationId,epoch,generation;public Integer confirmRound;
  public LocalDateTime appointmentStart,appointmentEnd,pickupStart,returnStart,issuedAt,expiresAt,invalidatedAt;public String lookupKeyId,lookupHash,codeKeyId,invalidatedReason;public byte[] codeCipher;}
 final class Binding {public Long id;public String canonicalVersion,payloadSha256,state;public byte[] canonicalBytes,resultBytes;public Integer resultVersion;}
 final class Attempt {public Long id,orderId;public String resultCode;public LocalDateTime attemptedAt;}
 final class Fence {public Long id,orderId,reservationId,storeId,userId,rescheduleId,oldEpoch,newEpoch,invalidatedGeneration;public LocalDateTime rescheduledAt;}
}
