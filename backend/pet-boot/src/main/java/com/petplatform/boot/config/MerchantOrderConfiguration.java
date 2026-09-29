package com.petplatform.boot.config;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import com.petplatform.order.api.query.OrderMerchantRejectFactsApi;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.order.biz.application.*;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.api.command.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.biz.apiimpl.ReservationRefundReleaseApiImpl;
import com.petplatform.task.core.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.*;

@Configuration(proxyBeanMethods=false)
public class MerchantOrderConfiguration {
    @Bean Object merchantOrderSwitchValidation(Environment e){
        boolean enabled=e.getProperty("pet.order.merchant.enabled",Boolean.class,false);
        boolean http=e.getProperty("pet.order.merchant.http.enabled",Boolean.class,false);
        boolean worker=e.getProperty("pet.order.merchant.worker.enabled",Boolean.class,false);
        if((http||worker)&&!enabled || enabled&&!e.getProperty("pet.payment.foundation.enabled",Boolean.class,false)
            ||http&&(!worker||!e.getProperty("pet.order.auto-confirm.enabled",Boolean.class,false)||!e.getProperty("pet.order.auto-confirm.worker.enabled",Boolean.class,false)))throw new IllegalStateException("Merchant order flags require complete payment, command and worker dependencies");
        return new Object();
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="pet.order.merchant.enabled",havingValue="true")
    static class Runtime {
        @Bean @ConditionalOnMissingBean(RefundOrderFactsApi.class)
        RefundOrderFactsApi merchantRefundPresence(DataSource s,ScheduleCapacityGuardApi guard){return new com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl(s,guard);}
        @Bean OrderMerchantRejectFactsApi merchantRejectFacts(DataSource s,ScheduleCapacityGuardApi g){return new OrderMerchantRejectFactsApiImpl(s,g);}
        @Bean MerchantOrderAuthorityApi merchantOrderAuthority(DataSource s,ScheduleCapacityGuardApi g){return new MerchantOrderAuthorityApiImpl(s,g);}
        @Bean @ConditionalOnMissingBean(MerchantOrderPorts.Protection.class)
        MerchantOrderPorts.Protection merchantOrderProtection(@Value("${pet.order.merchant.protection-key}") String key){
            try{return new MerchantOrderAesProtection(Base64.getDecoder().decode(key));}catch(RuntimeException bad){throw new IllegalStateException("Order protection configuration unavailable");}
        }
        @Bean MerchantOrderPorts.SessionAuthority merchantOrderSessions(UserAuthService auth){return user -> {
            if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Current session required");
            var session=auth.resolveSession(CBearerSessionFilter.bearer(attrs.getRequest()));
            if(!user.equals(session.userId())||!"ACTIVE".equals(session.userStatus()))throw new ApiException(CommonApiCodes.FORBIDDEN,"Account cannot write orders");
        };}
        @Bean ReservationRefundReleaseApi merchantRefundRelease(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,LateRefundService refunds){
            return new ReservationRefundReleaseApiImpl(s,ids,guard,refunds);
        }
        @Bean OrderMerchantRefundProjectionConsumer merchantRefundProjection(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderMerchantRejectFactsApi orders,LateRefundService refunds,ReservationRefundReleaseApi release){
            return new OrderMerchantRefundProjectionConsumer(s,ids,guard,orders,refunds,release);
        }
        @Bean MerchantOrderService merchantOrderService(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi auth,PaymentSuccessFactsApi payments,ReservationConfirmApi reservation,
            RefundOrderFactsApi presence,LateRefundService refunds,IntegrationEventPublisher outbox,MerchantOrderPorts.Protection protection,
            MerchantOrderPorts.Moderation moderation,MerchantOrderPorts.SessionAuthority sessions,
            RefundExecutionService execution,OrderMerchantRefundProjectionConsumer projection){
            return new MerchantOrderService(s,ids,guard,auth,payments,reservation,presence,refunds,outbox,protection,moderation,sessions);
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(name="pet.order.merchant.worker.enabled",havingValue="true")
        AsyncTaskWorker merchantRefundWorker(DataSource s,SnowflakeIdGenerator ids,RefundExecutionService execution){
            return AsyncTaskWorker.create(s,ids,"merchant-refund-"+UUID.randomUUID(),Clock.systemUTC(),TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_CHANNEL",List.of(Duration.ofSeconds(30),Duration.ofMinutes(1),Duration.ofMinutes(2),Duration.ofMinutes(5),Duration.ofMinutes(15),Duration.ofMinutes(30),Duration.ofHours(1)))),
                List.of(LateRefundConfiguration.registration("MERCHANT_REFUND_SUBMIT",execution,s),LateRefundConfiguration.registration("MERCHANT_REFUND_CHANNEL_QUERY",execution,s)));
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(name="pet.order.merchant.worker.enabled",havingValue="true")
        LateRefundConfiguration.DeadTaskReconciler merchantRefundReconciler(RefundExecutionService execution){return new LateRefundConfiguration.DeadTaskReconciler(execution);}
    }
}
