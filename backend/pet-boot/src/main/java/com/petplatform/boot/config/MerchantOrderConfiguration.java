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
        // Contracts 69/70 read slices ride the same runtime chain as the §4.1 list (store guard
        // + OWNER authority); their HTTP flags stay independently default-off.
        boolean customer=e.getProperty("pet.order.customer.http.enabled",Boolean.class,false);
        boolean stats=e.getProperty("pet.order.stats.http.enabled",Boolean.class,false);
        if((customer||stats)&&!enabled)throw new IllegalStateException("Merchant read slices require pet.order.merchant.enabled");
        return new Object();
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="pet.order.merchant.enabled",havingValue="true")
    static class Runtime {
        @Bean @ConditionalOnMissingBean(RefundOrderFactsApi.class)
        RefundOrderFactsApi merchantRefundPresence(DataSource s,ScheduleCapacityGuardApi guard){return new com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl(s,guard);}
        @Bean OrderMerchantRejectFactsApi merchantRejectFacts(DataSource s,ScheduleCapacityGuardApi g,ReservationConfirmApi reservation){return new OrderMerchantRejectFactsApiImpl(s,g,reservation);}
        @Bean MerchantOrderAuthorityApi merchantOrderAuthority(DataSource s,ScheduleCapacityGuardApi g){return new MerchantOrderAuthorityApiImpl(s,g);}
        /** Read-only store order list (contract 10 §4.1 supplement): same Runtime assembly as
         *  the contract-45 commands; the http switch keeps the route itself default off. */
        @Bean com.petplatform.order.api.query.MerchantOrderQueryApi merchantOrderQueryApi(DataSource s,
            ScheduleCapacityGuardApi guard,MerchantOrderAuthorityApi authority){
            return new com.petplatform.order.biz.apiimpl.MerchantOrderQueryApiImpl(s,guard,authority);
        }
        /** Contract 69 derived customers (read-only; masked user basics via the user module). */
        @Bean com.petplatform.order.api.query.MerchantCustomerQueryApi merchantCustomerQueryApi(DataSource s,
            ScheduleCapacityGuardApi guard,MerchantOrderAuthorityApi authority){
            return new com.petplatform.order.biz.apiimpl.MerchantCustomerQueryApiImpl(s,guard,authority,
                new com.petplatform.user.biz.apiimpl.UserQueryApiImpl(s));
        }
        /** Contract 70 stats (read-only); the review average rides the optional score port. */
        @Bean com.petplatform.order.api.query.MerchantStatsQueryApi merchantStatsQueryApi(DataSource s,
            ScheduleCapacityGuardApi guard,MerchantOrderAuthorityApi authority,
            org.springframework.beans.factory.ObjectProvider<com.petplatform.review.api.query.ReviewQueryApi> reviews){
            com.petplatform.order.api.query.StoreReviewScoreFactsApi scores = reviews.getIfAvailable() == null
                ? null
                : query -> {
                    var summary = reviews.getObject().getStoreScoreSummary(
                        new com.petplatform.review.api.query.ReviewQueryApi.StoreScoreSummaryQuery(
                            new com.petplatform.common.CommandContext(java.util.UUID.randomUUID().toString(),
                                "merchant-stats", com.petplatform.common.OperatorType.SYSTEM, null, "MINIAPP"),
                            query.merchantId(), query.storeId(), query.from(), query.to()));
                    return new com.petplatform.order.api.query.StoreReviewScoreFactsApi.ScoreSummary(
                        summary.reviewCount(), summary.avgScore());
                };
            // 排期利用率 (contract 70 expansion): SCH read-side aggregate over the store's own
            // windows + confirmed reservations; the port rides the same runtime chain.
            com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi utilization =
                new com.petplatform.schedule.biz.apiimpl.ScheduleUtilizationQueryApiImpl(s);
            return new com.petplatform.order.biz.apiimpl.MerchantStatsQueryApiImpl(s,guard,authority,scores,
                utilization,java.time.Clock.systemUTC());
        }
        /** Contract 69 expansion: the store's single internal customer note (OWNER write). */
        @Bean com.petplatform.order.api.query.MerchantCustomerNoteCommandApi merchantCustomerNoteCommandApi(
            DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,MerchantOrderAuthorityApi authority){
            return new com.petplatform.order.biz.apiimpl.MerchantCustomerNoteCommandApiImpl(s,guard,authority,ids);
        }
        @Bean @ConditionalOnMissingBean(MerchantOrderPorts.Protection.class)
        MerchantOrderPorts.Protection merchantOrderProtection(@Value("${pet.order.merchant.protection-key}") String key){
            try{return new MerchantOrderAesProtection(Base64.getDecoder().decode(key));}catch(RuntimeException bad){throw new IllegalStateException("Order protection configuration unavailable");}
        }
        @Bean MerchantOrderPorts.SessionAuthority merchantOrderSessions(UserAuthService auth){return user -> {
            if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Current session required");
            var session=auth.resolveSession(CBearerSessionFilter.bearer(attrs.getRequest()));
            if(!user.equals(session.userId())||!"ACTIVE".equals(session.userStatus()))throw new ApiException(CommonApiCodes.FORBIDDEN,"Account cannot write orders");
        };}
        /** Same-shape kernel as the contract-63 disposition face's reservationRefundRelease —
         *  both switches on must yield exactly one bean (FA-4, 2026-10-16): the conditional
         *  backs off whichever definition loses the registration race; the two definitions
         *  construct the identical ReservationRefundReleaseApiImpl from the same dependencies,
         *  so the survivor is behaviorally identical either way (same tie-break as
         *  merchantRefundPresence/dispositionRefundPresence above). */
        @Bean @ConditionalOnMissingBean(ReservationRefundReleaseApi.class)
        ReservationRefundReleaseApi merchantRefundRelease(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,LateRefundService refunds){
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
