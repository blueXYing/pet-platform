package com.petplatform.boot.config;

import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.command.OrderRefundApplicationApi;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.order.biz.apiimpl.OrderRefundApplicationApiImpl;
import com.petplatform.order.biz.apiimpl.OrderApplicationRefundProjectionConsumer;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.api.command.RefundApplicationTimeoutApi;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.api.command.*;
import com.petplatform.schedule.api.protection.*;
import com.petplatform.task.core.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.*;

/** Approved R1/R2 internal composition. Public HTTP and all flags remain closed by default. */
@Configuration(proxyBeanMethods=false)
public class RefundApplicationConfiguration {
    @Bean Object refundApplicationSwitchValidation(Environment e) {
        boolean enabled=e.getProperty("pet.refund.application.enabled",Boolean.class,false);
        boolean worker=e.getProperty("pet.refund.application.worker.enabled",Boolean.class,false);
        boolean http=e.getProperty("pet.refund.application.http.enabled",Boolean.class,false);
        if(http || worker&&!enabled || enabled&&(!e.getProperty("pet.payment.foundation.enabled",Boolean.class,false)
                || !e.getProperty("pet.order.merchant.enabled",Boolean.class,false)
                || !e.getProperty("pet.auth.c.enabled",Boolean.class,false)))
            throw new IllegalStateException("Refund application requires real session, merchant and payment dependencies; HTTP is not available");
        return new Object();
    }

    /** Uses the real auth service on every command/replay; no caller-declared identity. */
    public static RefundApplicationPorts.SessionAuthority sessionAuthority(UserAuthService auth) {
        Objects.requireNonNull(auth);
        return user -> {
            if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))
                throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Current session required");
            var session=auth.resolveSession(CBearerSessionFilter.bearer(attrs.getRequest()));
            if(!user.equals(session.userId())||!"ACTIVE".equals(session.userStatus()))
                throw new ApiException(CommonApiCodes.FORBIDDEN,"Account cannot apply or decide refunds");
        };
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="pet.refund.application.enabled",havingValue="true")
    static class Runtime {
        @Bean OrderRefundApplicationApiImpl orderRefundApplications(DataSource source,ScheduleCapacityGuardApi guard,
                SnowflakeIdGenerator ids,PaymentSuccessFactsApi payments,RefundOrderFactsApi refunds,
                ReservationConfirmApi reservations,ScheduleProtectionFactsApi schedule,
                ObjectProvider<RefundApplicationApprovalFactsApi> applications) {
            return new OrderRefundApplicationApiImpl(source,guard,ids,payments,refunds,reservations,schedule,applications::getObject);
        }
        @Bean RefundApplicationPorts.SessionAuthority refundApplicationSessions(UserAuthService auth) {return sessionAuthority(auth);}
        @Bean RefundApplicationPorts.OwnerAuthority refundApplicationOwners(MerchantOrderAuthorityApi authority) {
            return (c,merchant,store)->authority.requireOwner(merchant,store,new QueryContext(c.traceId(),c.operatorType(),c.operatorId()));
        }
        @Bean @ConditionalOnMissingBean(RefundApplicationPorts.Protection.class)
        RefundApplicationPorts.Protection refundApplicationProtection(@Value("${pet.refund.application.protection-key}") String key) {
            try{return new RefundApplicationAesProtection(Base64.getDecoder().decode(key));}
            catch(RuntimeException invalid){throw new IllegalStateException("Refund protection configuration unavailable");}
        }
        @Bean @ConditionalOnMissingBean(RefundApplicationPorts.ReasonPolicy.class)
        RefundApplicationPorts.ReasonPolicy refundApplicationReasons(Environment e) {
            String configured=e.getProperty("pet.refund.application.reason-codes");
            if(configured==null||configured.isBlank())throw new IllegalStateException("Approved refund reason configuration required");
            var codes=new HashSet<String>();
            for(String code:configured.split(",",-1)){
                code=code.trim();
                if(code.isBlank()||code.length()>64||!codes.add(code))throw new IllegalStateException("Refund reason configuration invalid");
            }
            var allowed=Set.copyOf(codes);
            return code->{if(!allowed.contains(code))throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Refund reason unavailable");};
        }
        @Bean RefundApplicationPorts.TaskRecovery refundApplicationTaskRecovery(DataSource source,SnowflakeIdGenerator ids) {
            var recovery=new JdbcAsyncTaskRecoverer(source,ids);
            return task->recovery.recover(task.taskKey(),"REFUND",task.taskType(),task.bizType(),task.bizId(),task.expectedVersion(),
                    task.payloadJson(),task.maxRetryCount(),task.retryPolicy(),task.availableAt());
        }
        @Bean RefundApplicationService refundApplications(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
                OrderRefundApplicationApi orders,PaymentSuccessFactsApi payments,IntegrationEventPublisher outbox,
                RefundApplicationPorts.SessionAuthority sessions,RefundApplicationPorts.OwnerAuthority owners,
                RefundApplicationPorts.ReasonPolicy reasons,RefundApplicationPorts.Moderation moderation,
                RefundApplicationPorts.Protection protection,RefundApplicationPorts.TaskRecovery recovery) {
            return new RefundApplicationService(source,ids,guard,orders,payments,outbox,sessions,owners,reasons,moderation,protection,recovery);
        }
        @Bean OrderApplicationRefundProjectionConsumer applicationRefundProjection(DataSource source,SnowflakeIdGenerator ids,
                ScheduleCapacityGuardApi guard,OrderRefundApplicationApi orders,OrderRefundApplicationFactsApi facts,
                LateRefundService refunds,ReservationRefundReleaseApi release) {
            return new OrderApplicationRefundProjectionConsumer(source,ids,guard,orders,facts,refunds,release);
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(name="pet.refund.application.worker.enabled",havingValue="true")
        AsyncTaskWorker refundApplicationWorker(DataSource source,SnowflakeIdGenerator ids,RefundApplicationService applications,
                RefundExecutionService execution) {
            return AsyncTaskWorker.create(source,ids,"refund-application-"+UUID.randomUUID(),Clock.systemUTC(),TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_APPLICATION",List.of(Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofMinutes(1),Duration.ofMinutes(5)),
                    "REFUND_CHANNEL",List.of(Duration.ofSeconds(30),Duration.ofMinutes(1),Duration.ofMinutes(2),Duration.ofMinutes(5),Duration.ofMinutes(15),Duration.ofMinutes(30),Duration.ofHours(1)))),
                List.of(registration("REFUND_MERCHANT_TIMEOUT",applications,source),registration("REFUND_APPLICATION_CREATE",applications,source),
                    LateRefundConfiguration.registration("APPLICATION_REFUND_SUBMIT",execution,source),
                    LateRefundConfiguration.registration("APPLICATION_REFUND_CHANNEL_QUERY",execution,source)));
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(name="pet.refund.application.worker.enabled",havingValue="true")
        ApplicationReconciler refundApplicationReconciler(RefundApplicationService applications,RefundExecutionService execution) {
            return new ApplicationReconciler(applications,execution);
        }
    }

    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record ApplicationPayload(String applicationId,String decisionId,String storeId,OffsetDateTime deadline) {}
    public static TaskRegistration<ApplicationPayload> registration(String type,RefundApplicationTimeoutApi applications,DataSource source) {
        if(!Set.of("REFUND_MERCHANT_TIMEOUT","REFUND_APPLICATION_CREATE").contains(type))throw new IllegalArgumentException();
        Objects.requireNonNull(applications);var clock=new TaskDatabaseClock(Objects.requireNonNull(source));
        return new TaskRegistration<>(new TaskHandler<>() {
            public String taskType(){return type;}
            public TaskExecutionResult execute(TaskExecutionContext task,ApplicationPayload p) {
                var c=new CommandContext("TASK:"+type+":"+p.applicationId(),task.traceId(),OperatorType.SYSTEM,null,"ASYNC_TASK");
                if(type.equals("REFUND_APPLICATION_CREATE")){
                    applications.createApproved(new RefundApplicationTimeoutApi.Create(c,p.applicationId(),p.decisionId(),p.storeId()));
                    return new TaskExecutionResult.Success("REFUND_CREATED");
                }
                var result=applications.handle(new RefundApplicationTimeoutApi.Timeout(c,p.applicationId(),p.storeId(),p.deadline()));
                if(result.done())return new TaskExecutionResult.Success("REFUND_APPLICATION_DECIDED");
                if(result.nextAt()==null)throw new IllegalStateException("Refund application deadline absent");
                Duration delay=Duration.between(clock.now().atOffset(ZoneOffset.UTC),result.nextAt()).plusMillis(250);
                return new TaskExecutionResult.Retry("REFUND_MERCHANT_NOT_DUE",delay.isNegative()?Duration.ofMillis(250):delay);
            }
        },lease->decode(type,lease),lease->"TASK:"+lease.taskKey());
    }
    private static ApplicationPayload decode(String type,TaskLease lease) {
        try{
            boolean timeout=type.equals("REFUND_MERCHANT_TIMEOUT");var n=JSON.readTree(lease.payloadJson());
            if(n==null||!n.isObject())throw new IllegalArgumentException();var fields=new HashSet<String>();n.fieldNames().forEachRemaining(fields::add);
            if(!fields.equals(timeout?Set.of("applicationId","storeId","merchantDeadline"):Set.of("applicationId","storeId","decisionId")))throw new IllegalArgumentException();
            for(String field:fields)if(!n.path(field).isTextual())throw new IllegalArgumentException();
            var codec=new DecimalPublicIdCodec();String application=n.path("applicationId").textValue(),store=n.path("storeId").textValue();
            long id=codec.fromApi(application);codec.fromApi(store);String decision=timeout?null:n.path("decisionId").textValue();
            OffsetDateTime deadline=timeout?OffsetDateTime.parse(n.path("merchantDeadline").textValue()):null;
            if(timeout){PublicContractChecks.requireMillisecondPrecision(deadline);if(!deadline.getOffset().equals(ZoneOffset.UTC))throw new IllegalArgumentException();}
            else codec.fromApi(decision);
            if(!type.equals(lease.taskType())||lease.bizId()!=id||!(type+":"+application).equals(lease.taskKey())
                    ||!Objects.equals(lease.expectedVersion(),timeout?0L:1L)||lease.maxRetryCount()!=8||lease.retryCount()<0
                    ||!"REFUND_APPLICATION".equals(lease.retryPolicy()))throw new IllegalArgumentException();
            return new ApplicationPayload(application,decision,store,deadline);
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid refund application task binding");}
    }
    public static final class ApplicationReconciler implements AutoCloseable {
        private static final System.Logger LOG=System.getLogger(ApplicationReconciler.class.getName());
        private final RefundApplicationService applications;private final RefundExecutionService execution;
        private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("refund-application-reconcile").factory());
        private boolean started;
        ApplicationReconciler(RefundApplicationService applications,RefundExecutionService execution){this.applications=applications;this.execution=execution;}
        public synchronized void start(){if(started)return;started=true;scheduler.scheduleWithFixedDelay(()->{
            try{applications.reconcileTasks();execution.reconcileDeadTasks();}
            catch(RuntimeException failure){LOG.log(System.Logger.Level.WARNING,"Refund application reconciliation failed: {0}",failure.getClass().getSimpleName());}
        },60,60,TimeUnit.SECONDS);}
        public synchronized void close(){scheduler.shutdown();}
    }
}
