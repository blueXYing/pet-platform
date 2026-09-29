package com.petplatform.order.biz.infrastructure.persistence.mapper;
import java.util.Map;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
public interface OrderRescheduleMapper {
 int reserve(Map<String,Object> values);
 OrderMerchantMapper.Binding binding(Map<String,Object> values);
 int succeed(@Param("id") long id,@Param("result") byte[] result);
 Integer duration(@Param("id") long id);
 int change(Map<String,Object> values);
 int record(Map<String,Object> values);
 int log(Map<String,Object> values);
 Record recordFor(@Param("id") long id);
 final class Record {
  public Long id,orderId,commandId,eventId,userId,storeId,reservationId,oldOrderVersion,newOrderVersion,
   oldReservationVersion,newReservationVersion,scheduleChangeId,verificationFenceId;
  public Integer fromRound,toRound;
  public String oldOrderStage,oldConfirmMode,oldTaskOutcome;
  public LocalDateTime oldConfirmedAt,oldConfirmDeadline,oldStart,oldEnd,oldPickup,oldReturn,
   newStart,newEnd,newPickup,newReturn,rescheduledAt,newConfirmDeadline;
 }
}
