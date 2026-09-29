package com.petplatform.order.biz.application;
import com.petplatform.common.*;
import com.petplatform.order.biz.infrastructure.persistence.OrderRescheduleStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper.Row;
import java.time.*;
import java.util.Objects;
import javax.sql.DataSource;
/** An immutable reschedule fact, never a rewritten paidAt, authorizes round one. */
public final class OrderConfirmEpoch {
 private OrderConfirmEpoch(){}
 public static void require(DataSource source,Row r){
  if(r==null||r.rescheduleCount==null||r.paidAt==null||r.confirmDeadline==null)throw bad();
  if(r.rescheduleCount==0){if(!r.paidAt.plusMinutes(30).equals(r.confirmDeadline))throw bad();return;}
  if(r.rescheduleCount!=1)throw bad();
  var p=new OrderRescheduleStore(source).mapper().recordFor(r.id);
  if(p==null||!Objects.equals(p.orderId,r.id)||!Objects.equals(p.userId,r.userId)||!Objects.equals(p.storeId,r.storeId)
    ||!Objects.equals(p.reservationId,r.reservationId)||p.fromRound==null||p.fromRound!=0||p.toRound==null||p.toRound!=1
    ||p.oldOrderVersion==null||p.newOrderVersion==null||p.newOrderVersion!=p.oldOrderVersion+1||r.version<p.newOrderVersion
    ||p.oldReservationVersion==null||p.newReservationVersion==null||p.newReservationVersion!=p.oldReservationVersion+1
    ||p.rescheduledAt==null||p.newConfirmDeadline==null||!p.rescheduledAt.plusMinutes(30).equals(p.newConfirmDeadline)
    ||!p.newConfirmDeadline.equals(r.confirmDeadline)||!Objects.equals(p.newStart,r.appointmentStartAt)||!Objects.equals(p.newEnd,r.appointmentEndAt)
    ||p.scheduleChangeId==null||p.scheduleChangeId<=0||p.verificationFenceId==null||p.verificationFenceId<=0
    ||p.eventId==null||p.eventId<=0||p.commandId==null||p.commandId<=0)throw bad();
 }
 public static void requireSchedule(DataSource source,Row r,com.petplatform.schedule.api.command.ReservationConfirmApi api,boolean releasedAllowed,QueryContext context){
  require(source,r);if(r.rescheduleCount==0)return;
  if(api==null)throw bad();var p=new OrderRescheduleStore(source).mapper().recordFor(r.id);
  api.assertRescheduled(Long.toString(r.id),Long.toString(r.reservationId),Long.toString(r.storeId),Long.toString(p.scheduleChangeId),p.newReservationVersion,
   r.appointmentStartAt.atOffset(ZoneOffset.UTC),r.appointmentEndAt.atOffset(ZoneOffset.UTC),releasedAllowed,context);
 }
 private static ApiException bad(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Confirmation epoch proof unavailable");}
}
