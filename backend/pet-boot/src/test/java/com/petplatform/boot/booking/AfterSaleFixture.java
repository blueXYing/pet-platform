package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.aftersale.api.command.AfterSaleCommandApi.*;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.CaseView;
import com.petplatform.aftersale.biz.application.*;
import com.petplatform.aftersale.biz.apiimpl.AfterSaleVerificationApiImpl;
import com.petplatform.boot.config.*;
import com.petplatform.common.*;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import com.petplatform.merchant.biz.apiimpl.BookingMerchantFactsApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.api.query.*;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.*;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import com.petplatform.thirdparty.biz.apiimpl.AfterSalePrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.infrastructure.provider.assetimage.ImageIoPrivateAssetWatermarkRenderer;
import com.petplatform.task.core.*;
import com.petplatform.verification.api.command.VerificationCompletionApi;
import com.petplatform.verification.api.command.VerificationCredentialApi;
import com.petplatform.verification.biz.application.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** One random MySQL schema per fixture. Never INSERTs a positive business outcome or proof. */
final class AfterSaleFixture implements AutoCloseable {
    static final String BUYER="710100", OTHER="710101", OWNER="710300", STORE="710302";
    static final AtomicLong IDS=new AtomicLong(9_140_000_000_000_000L);
    final RefundApplicationAcceptanceTest.F ordinary=new RefundApplicationAcceptanceTest.F();
    final AtomicBoolean fundingAvailable=new AtomicBoolean(true);
    final AtomicBoolean fundingAllowed=new AtomicBoolean(true);
    final AtomicBoolean moderationAvailable=new AtomicBoolean(true),reasonPolicyAvailable=new AtomicBoolean(true);
    final AtomicInteger moderationCalls=new AtomicInteger(),reasonPolicyCalls=new AtomicInteger();
    final AtomicBoolean loseFinalAck=new AtomicBoolean();
    final AfterSaleIdentityFixture identity;
    final AfterSaleAssetFixture assets;
    final AfterSalePrivateAssetApiImpl privateAssets;
    final AfterSaleService aftersales;
    final OrderAfterSaleApiImpl orderAftersales;
    final OrderRefundApplicationApiImpl ordinaryOrders;
    final RefundAfterSaleService refundAftersales;
    final VerificationCredentialService credentials;
    final VerificationCompletionAcceptanceTest.Components verification;
    final RefundFundingEligibilityFactsApi funding;
    final Clock clock;

    AfterSaleFixture() throws Exception {
        var db=ordinary.t.r.f.f.db;
        db.script("26-Admin-Auth-Schema-v0.1.sql");
        db.script("31-Private-Asset-Schema-v0.1.sql");
        db.script("50-AfterSale-Workflow-Schema-v0.1.sql");
        // Contract30 open-city directory uses the approved slug, not an administrative code.
        // This adjusts only inherited static merchant catalog data, never a business proof.
        db.jdbc.update("UPDATE merchant_profile_compat SET city_code='chengdu' WHERE merchant_id=710301");
        at(Instant.parse("2030-01-01T09:00:00Z"));
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return ordinary.source.instant();}};
        identity=new AfterSaleIdentityFixture(ordinary.source,IDS::incrementAndGet,clock);
        refreshBuyer();identity.loginExistingUser(OTHER,"13800000101");identity.loginExistingUser(OWNER,"13800000300");
        ordinary.sessions.set(RefundApplicationConfiguration.sessionAuthority(identity.users));
        var owner=new MerchantOrderAuthorityApiImpl(ordinary.source,ordinary.guard);
        var authority=new AfterSaleAuthorityAdapter(identity.users,identity.admins,identity.authorization,owner);
        assets=new AfterSaleAssetFixture(ordinary.source,IDS::incrementAndGet,identity);
        var services=new AtomicReference<AfterSaleService>();
        var refundServices=new AtomicReference<RefundAfterSaleService>();
        ordinaryOrders=new OrderRefundApplicationApiImpl(ordinary.source,ordinary.guard,IDS::incrementAndGet,
                ordinary.payments,new RefundOrderFactsApiImpl(ordinary.source,ordinary.guard),ordinary.reservations,
                ordinary.schedule,ordinary.approvals::get,services::get);
        var recovery=new JdbcAsyncTaskRecoverer(ordinary.source,IDS::incrementAndGet);
        ordinary.apps=new RefundApplicationService(ordinary.source,IDS::incrementAndGet,ordinary.guard,ordinaryOrders,
                ordinary.payments,ordinary.outbox,RefundApplicationConfiguration.sessionAuthority(identity.users),
                (context,merchant,store)->owner.requireOwner(merchant,store,new QueryContext(context.traceId(),context.operatorType(),context.operatorId())),
                code->{if(!"QA_REASON".equals(code))throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Unconfigured QA reason");},
                value->new RefundApplicationPorts.Approval(sha(value),"QA_ONLY_MODERATION",true),
                new RefundApplicationAesProtection(AfterSaleIdentityFixture.key(81)),
                spec->recovery.recover(spec.taskKey(),"REFUND",spec.taskType(),spec.bizType(),spec.bizId(),spec.expectedVersion(),
                        spec.payloadJson(),spec.maxRetryCount(),spec.retryPolicy(),spec.availableAt()));
        ordinary.approvals.set(ordinary.apps);
        privateAssets=new AfterSalePrivateAssetApiImpl(ordinary.source,IDS::incrementAndGet,
                AfterSaleAssetAdapters.authorizer(services::get,authority),assets.objectStore,assets.grantKeys,
                assets.reasonProtector,new ImageIoPrivateAssetWatermarkRenderer());
        orderAftersales=new OrderAfterSaleApiImpl(ordinary.source,ordinary.guard,IDS::incrementAndGet,
                ordinaryOrders,ordinary.payments,services::get,services::get,refundServices::get,
                (merchant,store,context)->{var scope=owner.requireResourceScope(merchant,store,context);
                    return new OrderAfterSaleApiImpl.Scope(scope.cityCode(),scope.scopeVersion());});
        refundAftersales=new RefundAfterSaleService(ordinary.source,IDS::incrementAndGet,ordinary.guard,
                orderAftersales,ordinary.payments,services::get,ordinary.outbox);
        refundServices.set(refundAftersales);
        funding=new RefundFundingEligibilityFactsApi(){
            public FundingEvidence requireForDecision(FundingCheck check,QueryContext context){return evidence(check);}
            public FundingEvidence requireForFirstSend(FundingCheck check,String committed,QueryContext context){
                if(committed==null||!committed.startsWith("QA_ONLY:"))throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Unknown test evidence");
                return evidence(check);
            }
            private FundingEvidence evidence(FundingCheck check){
                if(!fundingAvailable.get())throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"No authoritative funding provider");
                var at=now();String hash=RefundFundingEvidenceChecks.hash(check);
                return new FundingEvidence("QA_ONLY:"+hash,"QA_ONLY_AUTHORITY","qa-v1","qa-source","0",
                        "qa-proof:"+hash,hash,"UNSETTLED",fundingAllowed.get()?"ALLOWED":"DENIED","qa-v1",
                        check.requestedRefundAmount(),"CNY",at,at,at.plusMinutes(1),"qa-fence:"+hash);
            }
        };
        com.petplatform.event.api.IntegrationEventPublisher afsEvents=event->{
            ordinary.outbox.publish(event);
            if("AfterSaleResolvedEvent".equals(event.eventType())&&loseFinalAck.compareAndSet(true,false))
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization(){
                        // Runs after every beforeCommit recheck, including ADMIN's independent
                        // session reads. The next JDBC commit is the actual business transaction.
                        public void beforeCompletion(){ordinary.source.loseAtCommit.set(ordinary.source.commitCalls.get()+1);}
                        public void afterCompletion(int status){if(status!=STATUS_COMMITTED)ordinary.source.loseAtCommit.set(-1);}
                    });
        };
        aftersales=new AfterSaleService(ordinary.source,ordinary.guard,IDS::incrementAndGet,afsEvents,
                orderAftersales,orderAftersales,ordinary.apps,refundAftersales,funding,authority,
                new AfterSalePorts.ReasonPolicy(){
                    public com.petplatform.aftersale.api.query.AfterSaleQueryApi.Options options(){
                        if(!reasonPolicyAvailable.get())throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"QA reason dictionary unavailable");
                        return new com.petplatform.aftersale.api.query.AfterSaleQueryApi.Options(
                                List.of(new com.petplatform.aftersale.api.query.AfterSaleQueryApi.Option("QA_QUALITY","QA quality issue")),
                                List.of(new com.petplatform.aftersale.api.query.AfterSaleQueryApi.Option("QA_REFUND","QA requested resolution")));
                    }
                    public void requireCodes(String type,String demand){reasonPolicyCalls.incrementAndGet();
                        options();if(!"QA_QUALITY".equals(type)||!"QA_REFUND".equals(demand))
                        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Unconfigured QA reason");}
                },
                value->{moderationCalls.incrementAndGet();
                    if(!moderationAvailable.get())throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"QA moderation provider unavailable");
                    return new AfterSalePorts.Approval(sha(value),"QA_ONLY_MODERATION",true);},
                new AfterSaleAesProtection(AfterSaleIdentityFixture.key(61)),AfterSaleAssetAdapters.assets(privateAssets),
                new AfterSaleTaskAdapter(ordinary.source,IDS::incrementAndGet));
        services.set(aftersales);
        var orderCredentials=new OrderVerificationCredentialFactsApiImpl(ordinary.source,ordinary.guard,
                ordinary.payments,new RefundOrderFactsApiImpl(ordinary.source,ordinary.guard),ordinary.reservations,ordinary.schedule,owner);
        credentials=new VerificationCredentialService(ordinary.source,IDS::incrementAndGet,ordinary.guard,orderCredentials,
                new CredentialProtection("qa-afs",Map.of("qa-afs",AfterSaleIdentityFixture.key(71)),Map.of("qa-afs",AfterSaleIdentityFixture.key(72))),
                user->RefundApplicationConfiguration.sessionAuthority(identity.users).requireCurrent(user),
                VerificationAuthorityTestSupport.authority(identity.users,owner),ordinary.outbox);
        var verifiedService=new AtomicReference<VerificationCompletionService>();
        var invalidation=new AtomicReference<com.petplatform.aftersale.api.command.AfterSaleVerificationApi>();
        var verifyOrders=new OrderVerificationCommitApiImpl(ordinary.source,ordinary.guard,orderCredentials,
                IDS::incrementAndGet,ordinary.outbox,verifiedService::get,invalidation::get);
        var aftersaleVerification=new AfterSaleVerificationApiImpl(ordinary.source,ordinary.guard,verifyOrders,
                IDS::incrementAndGet,ordinary.outbox,services::get);
        invalidation.set(aftersaleVerification);
        var complete=new VerificationCompletionService(credentials,verifyOrders,aftersaleVerification);
        verifiedService.set(complete);
        verification=new VerificationCompletionAcceptanceTest.Components(complete,verifyOrders,aftersaleVerification);
    }

    String unverifiedOrder(){String order=ordinary.t.ready();identity.asUser(BUYER);return order;}
    String rejectedOrder(){String order=unverifiedOrder();at(now().plusSeconds(10).toInstant());identity.asUser(BUYER);var applied=ordinary.apps.apply(ordinary.applyCommand(order));
        identity.asUser(OWNER);assertEquals("REJECTED",ordinary.apps.decide(ordinary.decision(applied,"REJECT")).applicationStatus());return order;}
    String verifiedOrder(){String order=unverifiedOrder();at(now().plusSeconds(20).toInstant());assertEquals("VERIFIED",verify(verificationCommand(order)).resultCode());return order;}
    String secondVerifiedOrder() throws Exception {
        var foundation=ordinary.t.r.f.f;
        // Extend only static availability; the second order/payment/confirmation/verification
        // still traverse real APIs in this same schema, with a non-overlapping reservation.
        foundation.db.jdbc.update("UPDATE schedule_availability_window SET end_at='2030-01-01 13:00:00' WHERE id=710500");
        var start=OffsetDateTime.parse("2030-01-01T11:00:00Z");
        var previousEnd=foundation.db.jdbc.queryForObject("SELECT MAX(end_at) FROM schedule_reservation",
                LocalDateTime.class).atOffset(ZoneOffset.UTC);
        at(previousEnd.plusSeconds(1).toInstant());
        assertTrue(now().isBefore(start));
        // The inherited foundation uses Clock.systemUTC(); advancing only TimeSource would
        // still make its protection API see the first completed reservation as a future claim.
        // Recompose only booking with the same live controlled clock and its original source.
        var source=foundation.db.source;var guard=foundation.guard;
        var schedule=new ScheduleProtectionFactsApiImpl(source,guard);
        var staff=new MerchantCurrentStaffFactsApiImpl(source,guard);
        var orders=new OrderProtectionFactsApiImpl(source,guard,schedule,staff,clock);
        var proof=new ScheduleCapacityProofApiImpl(source,guard,schedule,staff,orders,clock,10_000);
        var hold=new ReservationHoldApiImpl(source,IDS::incrementAndGet,guard,schedule,proof,orders,clock);
        var booking=new OrderCreationApiImpl(source,IDS::incrementAndGet,new BookingUserFactsApiImpl(source,guard),
                new BookingMerchantFactsApiImpl(source,guard,new PersistentApplicationReviewFactsReader(source,IDS::incrementAndGet)),
                new BookingServiceFactsApiImpl(source,guard),guard,hold,new BookingCreateAcceptanceTest.InputProtector(),null,clock);
        String order=booking.create(new com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand(
                user(BUYER),STORE,"710401","710200","IN_STORE",start,start.plusMinutes(90),
                null,null,"710500",null,null,null,null,null)).orderId();
        var payment=foundation.prepare(order,UUID.randomUUID().toString());
        var notice=foundation.notice(payment,"SUCCESS","QA_AFS_SECOND_"+IDS.incrementAndGet(),payment.amount(),payment.amount());
        foundation.notification.receive(notice.headers(),notice.body());
        var event=foundation.db.jdbc.queryForObject("SELECT * FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.orderId'))=?",
                (rs,n)->new com.petplatform.event.api.DispatchedEvent(rs.getString("event_id"),rs.getString("event_type"),
                        rs.getInt("event_version"),rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
                        rs.getString("aggregate_type"),rs.getLong("aggregate_id"),rs.getString("trace_id"),rs.getString("payload")),order);
        foundation.result.consume(event);ordinary.t.confirm(order,0);
        at(start.plusSeconds(20).toInstant());refreshBuyer();identity.loginExistingUser(OWNER,"13800000300");identity.loginAdmin();
        assertEquals("VERIFIED",verify(verificationCommand(order)).resultCode());return order;
    }
    VerificationCompletionApi.Command verificationCommand(String order){
        identity.asUser(BUYER);String version=credentials.read(order,new QueryContext("afs-qa",OperatorType.USER,BUYER)).credentialVersion();
        var code=credentials.issue(new VerificationCredentialApi.Issue(user(BUYER),order,version,"INITIAL"));
        return new VerificationCompletionApi.Command(user(OWNER),order,STORE,code.code(),code.credentialVersion(),true);
    }
    VerificationCompletionApi.Receipt verify(VerificationCompletionApi.Command command){identity.asUser(OWNER);return verification.service().verify(command);}
    Receipt create(String order){return create(order,null);}
    Receipt create(String order,String statement){identity.asUser(BUYER);return aftersales.create(createCommand(order,List.of(),statement));}
    Create createCommand(String order,List<String> evidence,String statement){return new Create(user(BUYER),order,"QA_QUALITY","QA_REFUND",
            "Independent evidence of the service issue for this order",null,evidence,statement);}
    Receipt accept(Receipt current){var view=caseView(current);identity.asAdmin();return aftersales.accept(new Accept(admin(),current.afterSaleId(),current.version(),
            view.priorFinalCaseIds().isEmpty()?null:"Reviewed all prior final decisions; this is a distinct new problem",view.priorFinalCaseIds().isEmpty()?null:view.finalSetVersion()));}
    Receipt requestEvidence(Receipt current,String target,OffsetDateTime deadline){identity.asAdmin();return aftersales.requestSupplement(
            new RequestSupplement(admin(),current.afterSaleId(),current.version(),target,"Additional facts are needed for adjudication",deadline.toString()));}
    Receipt decide(Receipt current,String type,BigDecimal amount){identity.asAdmin();return aftersales.decide(new Decide(admin(),current.afterSaleId(),current.version(),type,amount,"Independent final decision based on reviewed evidence"));}
    CaseView caseView(Receipt receipt){identity.asUser(BUYER);return aftersales.getCase(user(BUYER),receipt.afterSaleId());}
    CommandContext user(String id){return new CommandContext(UUID.randomUUID().toString(),"afs-qa",OperatorType.USER,id,"MINIAPP");}
    CommandContext admin(){return identity.adminContext();}
    OffsetDateTime now(){return ordinary.source.instant().atOffset(ZoneOffset.UTC);}
    void at(Instant instant){ordinary.source.fixed.set(instant);}
    void refreshBuyer(){identity.loginExistingUser(BUYER,"13800000100");}
    OffsetDateTime anchor(String order,boolean verified){return ordinary.t.r.f.f.db.jdbc.queryForObject(
            "SELECT "+(verified?"verified_at":"appointment_start_at")+" FROM pet_order WHERE id=?",
            (rs,n)->rs.getTimestamp(1).toInstant().atOffset(ZoneOffset.UTC),Long.parseLong(order));}
    long count(String sql){return ordinary.count(sql);}String text(String sql){return ordinary.text(sql);}
    BigDecimal decimal(String sql){return ordinary.t.r.f.f.db.jdbc.queryForObject(sql,BigDecimal.class);}
    long decisionCount(){return count("SELECT COUNT(*) FROM aftersale_decision");}
    String currentMerchantScope(){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ordinary.source));
        transaction.setIsolationLevel(2);
        return transaction.execute(ignored->{
            var context=new QueryContext("afs-qa-current-scope",OperatorType.SYSTEM,null);
            ordinary.guard.acquire(List.of(STORE),context);
            return new MerchantOrderAuthorityApiImpl(ordinary.source,ordinary.guard)
                    .requireResourceScope("710301",STORE,context).scopeVersion();
        });
    }
    void fail(String point){
        if(point.equals("aftersale_command:UPDATE"))ordinary.sql("CREATE TRIGGER qa_refund_failure BEFORE UPDATE ON aftersale_command FOR EACH ROW BEGIN IF NEW.state='SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA final receipt persistence failure'; END IF; END");
        else ordinary.fail(point);
    }
    void unfail(){ordinary.unfail();}
    List<String> refundFailurePoints(){return List.of("order_operation_guard:INSERT","aftersale_decision:INSERT",
            "refund_order:INSERT","refund_execution:INSERT","refund_aftersale_proof:INSERT","async_task:INSERT",
            "aftersale_decision:UPDATE","aftersale_case:UPDATE","aftersale_transition:INSERT","aftersale_status_log:INSERT",
            "pet_order:UPDATE","order_aftersale_source_proof:UPDATE","order_aftersale_refund_commit:INSERT",
            "order_operation_guard:UPDATE","order_status_log:INSERT","integration_event_outbox:INSERT","aftersale_command:UPDATE");}
    void loseBusinessCommitAck(){loseFinalAck.set(true);}
    AsyncTaskWorker.Outcome runSupplementWorker(){
        try(var worker=AsyncTaskWorker.create(ordinary.source,IDS::incrementAndGet,"afs-qa-worker",clock,
                TaskWorkerSettings.defaults(),new TaskRetryDelays(Map.of("AFTERSALE_SUPPLEMENT",List.of(Duration.ofSeconds(1)))),
                List.of(new AfterSaleTaskAdapter(ordinary.source,IDS::incrementAndGet).registration(aftersales)))){
            return worker.runOne();
        }
    }
    static void code(String expected,org.junit.jupiter.api.function.Executable work){assertEquals(expected,assertThrows(ApiException.class,work).code());}
    static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public void close(){try{identity.close();}finally{ordinary.close();}}
}
