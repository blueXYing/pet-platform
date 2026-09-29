package com.petplatform.order.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderRescheduleCommitApi;
import com.petplatform.order.biz.application.OrderConfirmEpoch;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;
public final class OrderRescheduleCommitApiImpl implements OrderRescheduleCommitApi {
 private final DataSource source;private final ScheduleCapacityGuardApi guard;
 private final OrderRescheduleStore history;private final OrderAutoConfirmStore orders;
 public OrderRescheduleCommitApiImpl(DataSource source,ScheduleCapacityGuardApi guard){
  this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);
  history=new OrderRescheduleStore(source);orders=new OrderAutoConfirmStore(source);
 }
 public void requireCommitted(String order,String store,String reservation,String command,String change,long version,QueryContext context){
  guard.requireHeld(store,source);
  if(context==null||context.operatorType()!=OperatorType.SYSTEM)throw bad();
  var ids=new DecimalPublicIdCodec();var r=orders.lock(ids.fromApi(order));OrderConfirmEpoch.require(source,r);
  var p=history.mapper().recordFor(ids.fromApi(order));
  if(p==null||r.rescheduleCount!=1||!"PENDING_CONFIRM".equals(r.orderStage)||r.version.longValue()!=p.newOrderVersion.longValue()
   ||p.storeId!=ids.fromApi(store)||p.reservationId!=ids.fromApi(reservation)||p.commandId!=ids.fromApi(command)
   ||p.scheduleChangeId!=ids.fromApi(change)||p.newReservationVersion!=version)throw bad();
 }
 private static ApiException bad(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Atomic reschedule counterpart missing");}
}
