package com.petplatform.boot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.coupon.api.query.BookingCouponExposureApi;
import com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl;
import com.petplatform.order.api.command.OrderExpiryApi;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderExpiryApiImpl;
import com.petplatform.payment.api.query.BookingPaymentExposureApi;
import com.petplatform.payment.biz.apiimpl.BookingPaymentExposureApiImpl;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl;
import com.petplatform.task.core.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal, opt-in expiry for proven no-payment/no-coupon bookings. No channel calls or public commands. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pet.order.expiry",name="enabled",havingValue="true")
public class BookingExpiryConfiguration {
    private static final String TYPE="RESERVATION_HOLD_EXPIRE";
    @Bean BookingPaymentExposureApi bookingPaymentExposureApi(DataSource source,ScheduleCapacityGuardApi guard) {
        return new BookingPaymentExposureApiImpl(source,guard);
    }
    @Bean BookingCouponExposureApi bookingCouponExposureApi(DataSource source,ScheduleCapacityGuardApi guard) {
        return new BookingCouponExposureApiImpl(source,guard);
    }
    @Bean ReservationExpiryApi reservationExpiryApi(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,OrderProtectionFactsApi orders) {
        return new ReservationExpiryApiImpl(source,ids,guard,orders);
    }
    @Bean OrderExpiryApi orderExpiryApi(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,ReservationExpiryApi reservations,
            BookingPaymentExposureApi payments,BookingCouponExposureApi coupons) {
        return new OrderExpiryApiImpl(source,ids,guard,reservations,payments,coupons);
    }
    @Bean
    @ConditionalOnProperty(prefix="pet.order.expiry.worker",name="enabled",havingValue="true")
    public TaskRegistration<ExpiryPayload> bookingExpiryRegistration(OrderExpiryApi orders,ObjectProvider<Clock> clocks) {
        return registration(orders,clocks.getIfAvailable(Clock::systemUTC));
    }
    @Bean(initMethod="start",destroyMethod="close")
    @ConditionalOnProperty(prefix="pet.order.expiry.worker",name="enabled",havingValue="true")
    AsyncTaskWorker bookingExpiryWorker(DataSource source,SnowflakeIdGenerator ids,
            TaskRegistration<ExpiryPayload> bookingExpiryRegistration,ObjectProvider<Clock> clocks) {
        return AsyncTaskWorker.create(source,ids,"booking-expiry-"+UUID.randomUUID(),clocks.getIfAvailable(Clock::systemUTC),
                TaskWorkerSettings.defaults(),retryDelays(),List.of(bookingExpiryRegistration));
    }
    public static TaskRetryDelays retryDelays() {
        return new TaskRetryDelays(Map.of("FAST_INTERNAL",List.of(Duration.ofSeconds(5),Duration.ofSeconds(15),
                Duration.ofSeconds(60),Duration.ofMinutes(5),Duration.ofMinutes(15),Duration.ofMinutes(30))));
    }
    public static TaskRegistration<ExpiryPayload> registration(OrderExpiryApi orders,Clock clock) {
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override public String taskType() { return TYPE; }
            @Override public TaskExecutionResult execute(TaskExecutionContext task,ExpiryPayload payload) {
                var result=orders.expire(new ExpireOrderCommand(new CommandContext(task.requestId(),task.traceId(),
                        OperatorType.SYSTEM,null,"ASYNC_TASK"),payload.orderId(),payload.reservationId(),0,payload.deadline()));
                return switch(result) {
                    case CLOSED -> new TaskExecutionResult.Success("BOOKING_EXPIRED");
                    case NOOP -> new TaskExecutionResult.Success("NOOP");
                    case NOT_DUE -> new TaskExecutionResult.Retry("NOT_DUE",Duration.ofSeconds(5));
                };
            }
        },BookingExpiryConfiguration::decode,lease->"TASK:"+lease.taskKey());
    }
    private static ExpiryPayload decode(TaskLease lease) {
        try {
            JsonNode node=new ObjectMapper().readTree(lease.payloadJson());
            Set<String> fields=new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
            if(!node.isObject() || !fields.equals(Set.of("orderId","reservationId","expectedReservationVersion","expectedPaymentExpireAt"))
                    || !node.path("orderId").isTextual() || !node.path("reservationId").isTextual()
                    || !node.path("expectedPaymentExpireAt").isTextual()
                    || !node.path("expectedReservationVersion").isIntegralNumber()
                    || node.path("expectedReservationVersion").longValue()!=0) throw new IllegalArgumentException();
            String order=node.path("orderId").textValue(),reservation=node.path("reservationId").textValue();
            var ids=new DecimalPublicIdCodec(); ids.fromApi(order);
            if(ids.fromApi(reservation)!=lease.bizId() || !Objects.equals(0L,lease.expectedVersion())
                    || !TYPE.equals(lease.taskType()) || !(TYPE+":"+reservation+":0").equals(lease.taskKey())) throw new IllegalArgumentException();
            OffsetDateTime deadline=OffsetDateTime.parse(node.path("expectedPaymentExpireAt").textValue());
            PublicContractChecks.requireMillisecondPrecision(deadline);
            return new ExpiryPayload(order,reservation,deadline);
        } catch(Exception invalid) { throw new IllegalArgumentException("invalid booking expiry task generation"); }
    }
    public record ExpiryPayload(String orderId,String reservationId,OffsetDateTime deadline) {}
}
