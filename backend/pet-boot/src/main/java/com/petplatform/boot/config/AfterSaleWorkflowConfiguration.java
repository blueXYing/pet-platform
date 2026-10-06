package com.petplatform.boot.config;

import com.petplatform.aftersale.api.query.*;
import com.petplatform.aftersale.biz.application.*;
import com.petplatform.admin.api.query.*;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.api.query.*;
import com.petplatform.refund.api.query.*;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.*;
import com.petplatform.thirdparty.api.*;
import com.petplatform.thirdparty.biz.apiimpl.AfterSalePrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Approved workflow and opt-in HTTP adapters; no production funding fallback. */
@Configuration(proxyBeanMethods=false)
public class AfterSaleWorkflowConfiguration {
    @Bean Object afterSaleSwitchValidation(Environment e) {
        boolean enabled=on(e,"pet.aftersale.enabled");
        if(!enabled && (on(e,"pet.aftersale.http.enabled") || on(e,"pet.aftersale.worker.enabled") || on(e,"pet.aftersale.refund.enabled")))
            throw new IllegalStateException("Aftersale dependent switches require workflow");
        if(on(e,"pet.aftersale.http.enabled") && !on(e,"pet.auth.admin.enabled"))
            throw new IllegalStateException("Aftersale HTTP requires real admin sessions");
        if(enabled && (!on(e,"pet.refund.application.enabled") || !on(e,"pet.verification.completion.enabled")
                || !on(e,"pet.private-assets.enabled") || !on(e,"pet.auth.c.enabled")))
            throw new IllegalStateException("Aftersale requires real refund, verification, private assets and sessions");
        return new Object();
    }
    private static boolean on(Environment e,String key){return e.getProperty(key,Boolean.class,false);}

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="pet.aftersale.enabled",havingValue="true")
    static class Runtime {
        @Bean AfterSaleAuthorityAdapter afterSaleAuthority(UserAuthService users,AdminSessionQueryApi sessions,
                AdminAuthorizationQueryApi authorization,MerchantOrderAuthorityApi merchants){
            return new AfterSaleAuthorityAdapter(users,sessions,authorization,merchants);
        }
        @Bean OrderAfterSaleApiImpl orderAftersales(DataSource source,ScheduleCapacityGuardApi guard,SnowflakeIdGenerator ids,
                OrderRefundApplicationApiImpl ordinary,PaymentSuccessFactsApi payments,
                ObjectProvider<AfterSaleCaseFactsApi> cases,ObjectProvider<AfterSaleRefundFactsApi> decisions,
                ObjectProvider<RefundAfterSaleFactsApi> refunds,MerchantOrderAuthorityApi merchants){
            return new OrderAfterSaleApiImpl(source,guard,ids,ordinary,payments,cases::getObject,decisions::getObject,refunds::getObject,
                (merchant,store,context)->{var scope=merchants.requireResourceScope(merchant,store,context);
                    return new OrderAfterSaleApiImpl.Scope(scope.cityCode(),scope.scopeVersion());});
        }
        @Bean RefundAfterSaleService refundAftersales(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
                OrderAfterSaleApiImpl orders,PaymentSuccessFactsApi payments,ObjectProvider<AfterSaleRefundFactsApi> decisions,
                IntegrationEventPublisher outbox){return new RefundAfterSaleService(source,ids,guard,orders,payments,decisions::getObject,outbox);}
        @Bean AfterSalePrivateAssetApiImpl afterSalePrivateAssets(DataSource source,SnowflakeIdGenerator ids,
                ObjectProvider<AfterSaleEvidenceAccessApi> evidence,AfterSaleAuthorityAdapter authority,
                PrivateObjectStore objects,PrivateAssetGrantKeyProvider keys,PrivateAssetReasonProtector reasons,
                PrivateAssetWatermarkRenderer watermarks){
            return new AfterSalePrivateAssetApiImpl(source,ids,AfterSaleAssetAdapters.authorizer(evidence::getObject,authority),
                    objects,keys,reasons,watermarks);
        }
        @Bean @ConditionalOnMissingBean(AfterSalePorts.Protection.class)
        AfterSalePorts.Protection afterSaleProtection(@Value("${pet.aftersale.protection-key}") String key){
            try{return new AfterSaleAesProtection(Base64.getDecoder().decode(key));}
            catch(RuntimeException e){throw new IllegalStateException("Aftersale protection key unavailable");}
        }
        @Bean @ConditionalOnMissingBean(AfterSalePorts.ReasonPolicy.class)
        AfterSalePorts.ReasonPolicy afterSaleReasons(Environment e){
            var types=codes(e,"pet.aftersale.type-codes");var demands=codes(e,"pet.aftersale.demand-codes");
            var typeLabels=Binder.get(e).bind("pet.aftersale.type-labels",Bindable.mapOf(String.class,String.class)).orElse(Map.of());
            var demandLabels=Binder.get(e).bind("pet.aftersale.demand-labels",Bindable.mapOf(String.class,String.class)).orElse(Map.of());
            return new ConfiguredAfterSaleReasonPolicy(types,typeLabels,demands,demandLabels);
        }
        @Bean AfterSaleTaskAdapter afterSaleTasks(DataSource source,SnowflakeIdGenerator ids){return new AfterSaleTaskAdapter(source,ids);}
        @Bean AfterSaleService aftersales(DataSource source,ScheduleCapacityGuardApi guard,SnowflakeIdGenerator ids,
                IntegrationEventPublisher outbox,OrderAfterSaleApiImpl orders,RefundApplicationHistoryFactsApi ordinary,
                RefundAfterSaleService refunds,ObjectProvider<RefundFundingEligibilityFactsApi> funding,
                AfterSaleAuthorityAdapter authority,AfterSalePorts.ReasonPolicy reasons,AfterSalePorts.Moderation moderation,
                AfterSalePorts.Protection protection,AfterSalePrivateAssetApi assets,AfterSaleTaskAdapter tasks,Environment env){
            // The off switch cannot be bypassed by merely installing a provider bean.
            var authorityFacts=on(env,"pet.aftersale.refund.enabled")?funding.getObject():disabledFunding();
            return new AfterSaleService(source,guard,ids,outbox,orders,orders,ordinary,refunds,authorityFacts,
                    authority,reasons,moderation,protection,AfterSaleAssetAdapters.assets(assets),tasks);
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(name="pet.aftersale.worker.enabled",havingValue="true")
        AsyncTaskWorker afterSaleWorker(DataSource source,SnowflakeIdGenerator ids,AfterSaleService service,
                AfterSaleTaskAdapter tasks,ObjectProvider<RefundExecutionService> executions,Environment env){
            var registrations=new ArrayList<TaskRegistration<?>>();registrations.add(tasks.registration(service));
            // Closing new funding must retain queries/recovery for existing commitments.
            registrations.add(LateRefundConfiguration.registration("AFTERSALE_REFUND_SUBMIT",executions.getObject(),source));
            registrations.add(LateRefundConfiguration.registration("AFTERSALE_REFUND_CHANNEL_QUERY",executions.getObject(),source));
            return AsyncTaskWorker.create(source,ids,"aftersale-"+UUID.randomUUID(),Clock.systemUTC(),TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("AFTERSALE_SUPPLEMENT",List.of(Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofMinutes(1)),
                    "REFUND_CHANNEL",List.of(Duration.ofSeconds(30),Duration.ofMinutes(1),Duration.ofMinutes(5)))),registrations);
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(name="pet.aftersale.worker.enabled",havingValue="true")
        Reconciler afterSaleReconciler(AfterSaleService service,RefundExecutionService execution){return new Reconciler(service,execution);}
    }
    private static Set<String> codes(Environment e,String key){
        String value=e.getProperty(key);if(value==null||value.isBlank())throw new IllegalStateException("Approved aftersale catalog required");
        var result=new HashSet<String>();for(String code:value.split(",",-1)){
            code=code.trim();if(!code.matches("[A-Z][A-Z0-9_]{0,63}")||!result.add(code))throw new IllegalStateException("Invalid aftersale catalog");
        }return Set.copyOf(result);
    }
    private static RefundFundingEligibilityFactsApi disabledFunding(){return new RefundFundingEligibilityFactsApi(){
        public FundingEvidence requireForDecision(FundingCheck c,QueryContext q){throw unavailable();}
        public FundingEvidence requireForFirstSend(FundingCheck c,String committed,QueryContext q){throw unavailable();}
        private ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Aftersale refund funding is disabled");}
    };}
    static final class Reconciler implements AutoCloseable {
        private static final System.Logger LOG=System.getLogger(Reconciler.class.getName());
        private final AfterSaleService service;
        private final RefundExecutionService execution;
        private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("aftersale-reconcile").factory());
        Reconciler(AfterSaleService service,RefundExecutionService execution){this.service=service;this.execution=execution;}
        public void start(){scheduler.scheduleWithFixedDelay(()->{try{service.reconcileTasks();execution.reconcileDeadTasks();}
            catch(RuntimeException failure){LOG.log(System.Logger.Level.WARNING,"Aftersale reconciliation failed: {0}",failure.getClass().getSimpleName());}},60,60,TimeUnit.SECONDS);}
        public void close(){scheduler.shutdown();}
    }
}
