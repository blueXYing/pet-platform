package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.biz.apiimpl.*;
import com.petplatform.payment.biz.application.*;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.*;
import com.petplatform.refund.api.command.RefundApplicationTimeoutApi.*;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.task.core.*;
import com.petplatform.boot.config.RefundApplicationConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real creation/decision APIs and MySQL. Clock changes use MySQL session time, never seeded decisions. */
class RefundApplicationAcceptanceTest {
    static final AtomicLong IDS=new AtomicLong(8_980_000_000_000_000L);
    static final ObjectMapper JSON=new ObjectMapper();
    @Test void actualCompletedOrderAppliesOnceWithPrivateReasonAndAtomicIntent(){try(var f=new F()){
        String order=f.ready(true);var c=f.applyCommand(order);var r=f.apps.apply(c);
        assertEquals("PENDING_MERCHANT",r.applicationStatus());assertEquals("0",r.applicationVersion());assertEquals(r,f.apps.apply(c));
        assertEquals(24L,f.count("SELECT TIMESTAMPDIFF(HOUR,created_at,merchant_deadline) FROM refund_application"));
        assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application"));assertEquals(r.applicationId(),f.text("SELECT CAST(current_refund_application_id AS CHAR) FROM pet_order"));
        assertEquals("COMPLETED",f.text("SELECT order_stage FROM pet_order"));assertEquals(1,f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_MERCHANT_TIMEOUT'"));
        assertEquals(1,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='RefundApplicationCreatedEvent.v1'"));
        assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application WHERE reason_text IS NOT NULL"));
        assertFalse(new String(f.t.r.f.f.db.jdbc.queryForObject("SELECT reason_text_cipher FROM refund_application",byte[].class),StandardCharsets.UTF_8).contains("private refund reason"));
        assertFalse(f.text("SELECT payload FROM integration_event_outbox WHERE event_type='RefundApplicationCreatedEvent.v1'").contains("private refund reason"));
        code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->f.apps.apply(new Apply(c.context(),order,"QA_REASON","changed")));
    }}
    @Test void rejectionThenNewApplicationHasFreshDeadlineAndOldRequestsCannotChangeIt(){try(var f=new F()){
        String o=f.ready(true);var c=f.applyCommand(o);var first=f.apps.apply(c);var reject=f.decision(first,"REJECT");var rejected=f.apps.decide(reject);
        f.source.shiftSeconds.set(60);var second=f.apps.apply(f.applyCommand(o));
        assertNotEquals(first.applicationId(),second.applicationId());assertTrue(OffsetDateTime.parse(second.merchantDeadline()).isAfter(OffsetDateTime.parse(first.merchantDeadline())));
        assertEquals(first,f.apps.apply(c));assertEquals(rejected,f.apps.decide(reject));assertTrue(f.apps.handle(f.timeout(first)).done());
        assertEquals(second.applicationId(),f.text("SELECT CAST(current_refund_application_id AS CHAR) FROM pet_order"));assertEquals("PENDING_MERCHANT",f.text("SELECT refund_application_status FROM pet_order"));
        assertEquals(2,f.count("SELECT COUNT(*) FROM refund_application"));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_decision"));assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));
        assertNotNull(f.t.r.f.f.db.jdbc.queryForObject("SELECT reason_cipher FROM refund_application_decision",byte[].class));
    }}
    @Test void lostActualCommitAckRecoversOriginalApplicationDecisionAndRefund(){try(var f=new F()){
        String o=f.ready(true);var apply=f.applyCommand(o);f.source.loseAtCommit.set(f.source.commitCalls.get()+2);
        assertThrows(ApiException.class,()->f.apps.apply(apply));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application"));var a=f.apps.apply(apply);
        var decide=f.decision(a,"APPROVE");f.source.loseAtCommit.set(f.source.commitCalls.get()+2);
        assertThrows(ApiException.class,()->f.apps.decide(decide));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_decision"));var d=f.apps.decide(decide);
        f.source.loseAtCommit.set(f.source.commitCalls.get()+1);assertThrows(ApiException.class,()->f.apps.createApproved(f.create(d)));
        String original=f.text("SELECT CAST(id AS CHAR) FROM refund_order");f.apps=f.build();assertEquals(original,f.apps.createApproved(f.create(d)));
        assertEquals(a,f.apps.apply(apply));assertEquals(d,f.apps.decide(decide));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order"));
        assertEquals(3,f.source.lostAcks.get());assertEquals(1,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='RefundOrderCreatedEvent.v1'"));
    }}
    @Test void sameKeyConcurrentApplyReplaysAndDifferentKeysCannotExtendDeadline()throws Exception{try(var f=new F();var pool=Executors.newFixedThreadPool(2)){
        String o=f.ready(true);var c=f.applyCommand(o);var latch=new CountDownLatch(1);
        var a=pool.submit(()->{latch.await();return f.apps.apply(c);});var b=pool.submit(()->{latch.await();return f.apps.apply(c);});latch.countDown();var r=a.get(20,TimeUnit.SECONDS);assertEquals(r,b.get(20,TimeUnit.SECONDS));
        assertThrows(ApiException.class,()->f.apps.apply(f.applyCommand(o)));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application"));
        assertEquals(r.merchantDeadline(),f.apps.apply(c).merchantDeadline());
    }}
    @Test void earlyTimeoutRetriesAndExactDeadlineExcludesMerchant(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var early=f.apps.handle(f.timeout(r));assertFalse(early.done());assertEquals(OffsetDateTime.parse(r.merchantDeadline()),early.nextAt());
        assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application_decision"));f.source.fixed.set(OffsetDateTime.parse(r.merchantDeadline()).toInstant());
        code("REFUND_MERCHANT_DEADLINE_PASSED",()->f.apps.decide(f.decision(r,"APPROVE")));
        assertTrue(f.apps.handle(f.timeout(r)).done());assertTrue(f.apps.handle(f.timeout(r)).done());assertEquals("AUTO_APPROVED",f.text("SELECT status FROM refund_application"));
        assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_decision"));assertEquals(1,f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));
    }}
    @Test void beforeServiceRouteIsNotMistakenForMerchant24HoursAndWrongBuyerCannotApply(){try(var f=new F()){
        String o=f.t.ready();code("REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED",()->f.apps.apply(f.applyCommand(o)));
        code(CommonApiCodes.FORBIDDEN,()->f.apps.apply(new Apply(ctx("710101"),o,"QA_REASON",null)));
        assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application"));
    }}
    @Test void rejectRequiresMeaningfulReasonAndModerationFailureRetainsAdmission(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));
        for(String reason:Arrays.asList(null,"","  ","x".repeat(501)))code(CommonApiCodes.INVALID_ARGUMENT,()->f.apps.decide(new Decide(ctx("710300"),r.applicationId(),"0","REJECT",reason)));
        var c=f.decision(r,"REJECT");f.moderationAllowed.set(false);code(CommonApiCodes.INVALID_ARGUMENT,()->f.apps.decide(c));
        f.moderationAllowed.set(true);code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->f.apps.decide(new Decide(c.context(),c.applicationId(),"0","REJECT","different")));
        assertEquals("REJECTED",f.apps.decide(c).applicationStatus());
    }}
    @Test void applicationWriteFailuresRollBackEverythingExceptCanonicalAdmission(){try(var f=new F()){
        String o=f.ready(true);var c=f.applyCommand(o);
        for(String operation:List.of("refund_application:INSERT","order_refund_application_proof:INSERT","pet_order:UPDATE","order_status_log:INSERT","async_task:INSERT","integration_event_outbox:INSERT","refund_application_command:UPDATE")){
            f.fail(operation);assertThrows(ApiException.class,()->f.apps.apply(c));f.unfail();
            assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application"));assertEquals(0,f.count("SELECT COUNT(*) FROM order_refund_application_proof"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM pet_order WHERE current_refund_application_id IS NOT NULL"));assertEquals("RESERVED",f.text("SELECT state FROM refund_application_command"));
        }
        assertNotNull(f.apps.apply(c).applicationId());
    }}
    @Test void decisionWriteFailuresLeavePendingWithOneRetryableBinding(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var c=f.decision(r,"APPROVE");
        for(String operation:List.of("refund_application_decision:INSERT","refund_application:UPDATE","order_refund_application_proof:UPDATE","pet_order:UPDATE","order_status_log:INSERT","async_task:INSERT","integration_event_outbox:INSERT","refund_application_command:UPDATE")){
            f.fail(operation);assertThrows(ApiException.class,()->f.apps.decide(c));f.unfail();
            assertEquals("PENDING_MERCHANT",f.text("SELECT status FROM refund_application"));assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application_decision"));assertEquals(0,f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));
        }
        assertEquals("APPROVED",f.apps.decide(c).applicationStatus());
    }}
    @Test void pendingAndApprovedApplicationsAllowRealVerificationBeforeCreation(){for(boolean approveFirst:List.of(false,true))try(var f=new F()){
        String o=f.ready(false);var credential=f.t.issue(o,"INITIAL");var r=f.apps.apply(f.applyCommand(o));var d=approveFirst?f.apps.decide(f.decision(r,"APPROVE")):null;
        assertEquals("VERIFIED",VerificationCompletionAcceptanceTest.build(f.t).verify(VerificationCompletionAcceptanceTest.command(o,credential)).resultCode());
        if(d==null)d=f.apps.decide(f.decision(r,"APPROVE"));String verified=f.text("SELECT CAST(verified_at AS CHAR) FROM pet_order");
        String refund=f.apps.createApproved(f.create(d));assertNotNull(refund);assertEquals(verified,f.text("SELECT CAST(verified_at AS CHAR) FROM pet_order"));
        assertEquals("COMPLETED",f.text("SELECT order_stage FROM pet_order"));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order"));
    }}
    @Test void createdRefundBlocksVerificationAndApplicationEvenIfChannelHasNotRun(){try(var f=new F()){
        String o=f.ready(false);var credential=f.t.issue(o,"INITIAL");var r=f.apps.apply(f.applyCommand(o));var d=f.apps.decide(f.decision(r,"APPROVE"));
        String refund=f.apps.createApproved(f.create(d));assertEquals(refund,f.apps.createApproved(f.create(d)));
        assertThrows(ApiException.class,()->VerificationCompletionAcceptanceTest.build(f.t).verify(VerificationCompletionAcceptanceTest.command(o,credential)));
        assertThrows(ApiException.class,()->f.apps.apply(f.applyCommand(o)));assertEquals(0,f.count("SELECT COUNT(*) FROM verification_record"));
        assertEquals(d,f.apps.decide(f.decisionWithContext(d,"APPROVE",f.lastDecision.context())));
        assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
    }}
    @Test void createWriteFailuresKeepApprovedDecisionAndRecoverWithoutOriginalSession(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var d=f.apps.decide(f.decision(r,"APPROVE"));var c=f.create(d);f.t.r.f.sessionActive.set(false);
        for(String operation:List.of("refund_order:INSERT","refund_execution:INSERT","refund_application:UPDATE","order_refund_application_commit:INSERT","pet_order:UPDATE","integration_event_outbox:INSERT","async_task:INSERT")){
            f.fail(operation);assertThrows(ApiException.class,()->f.apps.createApproved(c));f.unfail();
            assertEquals("APPROVED",f.text("SELECT status FROM refund_application"));assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(0,f.count("SELECT COUNT(*) FROM order_refund_application_commit"));assertEquals(0,f.count("SELECT COUNT(*) FROM order_operation_guard WHERE operation_type='CREATE_REFUND'"));
        }
        assertNotNull(f.apps.createApproved(c));assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order"));
    }}
    @Test void durableCreationWorkerRecoversApprovedCommitAfterRestart(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var d=f.apps.decide(f.decision(r,"APPROVE"));
        f.sql("UPDATE async_task SET status='DEAD',retry_count=8 WHERE task_type='REFUND_APPLICATION_CREATE'");
        f.apps=f.build();assertEquals(1,f.apps.reconcileTasks());assertEquals("READY",f.text("SELECT status FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));
        try(var worker=AsyncTaskWorker.create(f.source,IDS::incrementAndGet,"ordinary-create-qa",Clock.systemUTC(),TaskWorkerSettings.defaults(),
            new TaskRetryDelays(Map.of("REFUND_APPLICATION",List.of(Duration.ofSeconds(1)))),List.of(RefundApplicationConfiguration.registration("REFUND_APPLICATION_CREATE",f.apps,f.source)))){
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED,worker.runOne());
        }
        assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(1,f.count("SELECT COUNT(*) FROM async_task WHERE last_result_code='REFUND_CREATED' AND status='SUCCEEDED'"));
        assertNotNull(f.apps.createApproved(f.create(d)));
    }}
    @Test void duePendingMissingDeadAndCanceledTimeoutTasksRecoverFromRealApplication(){for(String state:List.of("MISSING","DEAD","CANCELED"))try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));f.source.fixed.set(OffsetDateTime.parse(r.merchantDeadline()).plusSeconds(1).toInstant());
        if(state.equals("MISSING"))f.sql("DELETE FROM async_task WHERE task_type='REFUND_MERCHANT_TIMEOUT'");else f.sql("UPDATE async_task SET status='"+state+"' WHERE task_type='REFUND_MERCHANT_TIMEOUT'");
        assertEquals(1,f.apps.reconcileTasks());assertEquals("READY",f.text("SELECT status FROM async_task WHERE task_type='REFUND_MERCHANT_TIMEOUT'"));
        assertTrue(f.apps.handle(f.timeout(r)).done());assertEquals("AUTO_APPROVED",f.text("SELECT status FROM refund_application"));assertNotNull(f.apps.createApproved(f.create(f.receipt())));
    }}
    @Test void corruptCandidateDoesNotStarveLaterRealApprovedApplication(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));f.apps.decide(f.decision(r,"APPROVE"));
        // Deliberately corrupt storage row, never a claimed legitimate business source or authorization.
        f.sql("INSERT INTO refund_application(id,application_no,order_id,applicant_user_id,status,reason_code,requested_amount,merchant_deadline,request_id,created_at,updated_at,store_id,merchant_id,reservation_id,payment_id,payment_no,payment_success_event_id,channel_trade_no,paid_at,created_command_id,created_event_id,version,decision_id,decided_at,decided_by) SELECT id-1,application_no-1,order_id-1,applicant_user_id,status,reason_code,requested_amount,merchant_deadline,request_id,created_at,updated_at,store_id,merchant_id,reservation_id,payment_id,payment_no,payment_success_event_id,channel_trade_no,paid_at,created_command_id-1,created_event_id-1,version,decision_id-1,decided_at,decided_by FROM refund_application");
        f.sql("UPDATE async_task SET status='DEAD' WHERE task_type='REFUND_APPLICATION_CREATE'");
        assertEquals(2,f.apps.reconcileTasks());assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue WHERE status='OPEN' AND issue_code='APPLICATION_PROOF_INVALID'"));
        assertEquals("READY",f.text("SELECT status FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));
    }}
    @Test void corruptCanonicalAndTaskMetadataProduceDurableIssuesAndResolveAfterRepair(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));f.apps.decide(f.decision(r,"APPROVE"));
        String originalHash=f.text("SELECT payload_sha256 FROM refund_application_command WHERE command_namespace=CAST('refund.application.apply' AS BINARY)");
        f.sql("UPDATE refund_application_command SET payload_sha256=REPEAT('f',64) WHERE command_namespace=CAST('refund.application.apply' AS BINARY)");
        assertEquals(1,f.apps.reconcileTasks());assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue WHERE status='OPEN' AND issue_code='APPLICATION_PROOF_INVALID'"));
        f.t.r.f.f.db.jdbc.update("UPDATE refund_application_command SET payload_sha256=? WHERE command_namespace=CAST('refund.application.apply' AS BINARY)",originalHash);
        String originalPayload=f.text("SELECT payload_json FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'");f.sql("UPDATE async_task SET status='DEAD',payload_json=JSON_SET(payload_json,'$.decisionId','999') WHERE task_type='REFUND_APPLICATION_CREATE'");
        f.apps.reconcileTasks();assertEquals(1,f.apps.reconcileTasks());assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue WHERE status='OPEN' AND issue_code='APPLICATION_TASK_CONFLICT'"));
        f.t.r.f.f.db.jdbc.update("UPDATE async_task SET payload_json=? WHERE task_type='REFUND_APPLICATION_CREATE'",originalPayload);
        f.apps.reconcileTasks();assertEquals(1,f.apps.reconcileTasks());assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue WHERE status='OPEN'"));assertEquals(2,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue WHERE status='RESOLVED'"));
        assertEquals("READY",f.text("SELECT status FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));
    }}
    @Test void simultaneousOwnerAndDueTimeoutHaveOnlyOneDecision()throws Exception{try(var f=new F();var pool=Executors.newFixedThreadPool(2)){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));f.source.fixed.set(OffsetDateTime.parse(r.merchantDeadline()).toInstant());var latch=new CountDownLatch(1);
        var owner=pool.submit(()->{latch.await();return assertThrows(ApiException.class,()->f.apps.decide(f.decision(r,"APPROVE"))).code();});
        var timeout=pool.submit(()->{latch.await();return f.apps.handle(f.timeout(r));});latch.countDown();assertTrue(timeout.get(20,TimeUnit.SECONDS).done());assertNotNull(owner.get(20,TimeUnit.SECONDS));
        assertEquals(1,f.count("SELECT COUNT(*) FROM refund_application_decision"));assertEquals("AUTO_APPROVED",f.text("SELECT status FROM refund_application"));
    }}
    @Test void concurrentRealVerificationAndCreateUseTheSameStoreGuard()throws Exception{try(var f=new F();var pool=Executors.newFixedThreadPool(2)){
        String o=f.ready(false);var credential=f.t.issue(o,"INITIAL");var r=f.apps.apply(f.applyCommand(o));var d=f.apps.decide(f.decision(r,"APPROVE"));
        var verification=VerificationCompletionAcceptanceTest.build(f.t);var latch=new CountDownLatch(1);
        var verify=pool.submit(()->{latch.await();try{return (Object)verification.verify(VerificationCompletionAcceptanceTest.command(o,credential));}catch(ApiException e){return e.code();}});
        var create=pool.submit(()->{latch.await();return f.apps.createApproved(f.create(d));});latch.countDown();assertNotNull(create.get(20,TimeUnit.SECONDS));
        Object result=verify.get(20,TimeUnit.SECONDS);if(result instanceof com.petplatform.verification.api.command.VerificationCompletionApi.Receipt receipt){assertEquals("VERIFIED",receipt.resultCode());assertEquals(1,f.count("SELECT COUNT(*) FROM verification_record"));}
        else{assertEquals("VERIFICATION_BLOCKED_BY_REFUND",result);assertEquals(0,f.count("SELECT COUNT(*) FROM verification_record"));}
        assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(1,f.count("SELECT COUNT(*) FROM order_refund_application_commit"));
    }}
    @Test void channelSourceCorruptionRecordsAnIssueWithoutMutatingMoney(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var d=f.apps.decide(f.decision(r,"APPROVE"));String refund=f.apps.createApproved(f.create(d));var runtime=f.runtime(false);
        f.sql("UPDATE async_task SET status='DEAD' WHERE task_type='APPLICATION_REFUND_SUBMIT'");f.sql("UPDATE refund_execution SET source_decision_id=source_decision_id+1");
        assertEquals(1,runtime.execution.reconcileDeadTasks());assertEquals(1,f.count("SELECT COUNT(*) FROM refund_reconciliation_issue WHERE issue_code='REFUND_SOURCE_PROOF_INVALID' AND status='OPEN'"));
        assertEquals("CREATED",f.text("SELECT status FROM refund_order"));assertEquals(0,runtime.sends.get());assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
        f.sql("UPDATE refund_execution SET source_decision_id=source_decision_id-1");assertTrue(runtime.execution.execute(refund,"710302",false,"qa","APPLICATION").done());
        assertEquals(1,f.count("SELECT COUNT(*) FROM refund_reconciliation_issue WHERE status='RESOLVED'"));
    }}
    @Test void unavailableOrderStorageIsNotMisclassifiedAsCorruptBusinessProof()throws Exception{try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));f.apps.decide(f.decision(r,"APPROVE"));f.sql("RENAME TABLE order_refund_application_proof TO qa_order_refund_proof_unavailable");
        try{assertThrows(ApiException.class,()->f.apps.reconcileTasks());assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue"));}
        finally{f.sql("RENAME TABLE qa_order_refund_proof_unavailable TO order_refund_application_proof");}
        assertEquals(1,f.apps.reconcileTasks());
        f.apps=f.build();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setIsolationLevel(2);
        try(var pool=Executors.newSingleThreadExecutor()){
            var lock=pool.submit(()->tx.executeWithoutResult(s->{f.guard.acquire(List.of("710302"),new QueryContext("qa-lock",OperatorType.SYSTEM,null));held.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}));
            assertTrue(held.await(3,TimeUnit.SECONDS));try{assertThrows(ApiException.class,()->f.apps.reconcileTasks());assertEquals(0,f.count("SELECT COUNT(*) FROM refund_application_reconciliation_issue"));}finally{release.countDown();}lock.get(5,TimeUnit.SECONDS);
        }
    }}
    @Test void realAuthRejectsForgedBuyerFrozenOwnerLogoutAndReplayAfterRevocation()throws Exception{try(var f=new F()){
        String o=f.ready(true);var cache=new VerificationCompletionAcceptanceTest.SessionCache();
        var auth=new com.petplatform.user.biz.application.UserAuthService(f.source,IDS::incrementAndGet,Clock.systemUTC(),new com.petplatform.user.biz.application.WechatSessionProvider(){public WechatIdentity exchangeIdentity(String code){throw new ProofRejected();}public String exchangePhone(String code){throw new ProofRejected();}},cache,new com.petplatform.user.biz.application.UserAuthService.MiniAuthPolicy(300,3600,60,10,5));
        f.sessions.set(RefundApplicationConfiguration.sessionAuthority(auth));String buyer=f.login(cache,"710100"),owner=f.login(cache,"710300");
        f.bearer(owner);code(CommonApiCodes.FORBIDDEN,()->f.apps.apply(f.applyCommand(o)));f.bearer(buyer);var c=f.applyCommand(o);var r=f.apps.apply(c);
        f.bearer(owner);f.sql("UPDATE user_account SET status='FROZEN' WHERE id=710300");assertThrows(ApiException.class,()->f.apps.decide(f.decision(r,"APPROVE")));f.sql("UPDATE user_account SET status='ACTIVE' WHERE id=710300");
        f.sql("UPDATE merchant_store SET status='FROZEN'");assertThrows(ApiException.class,()->f.apps.decide(f.decision(r,"APPROVE")));
        f.sql("UPDATE merchant_store SET status='OFFLINE'");f.sql("UPDATE merchant SET status='OFFLINE'");
        var d=f.decision(r,"REJECT");f.apps.decide(d);f.sql("UPDATE merchant SET owner_user_id=710101");assertThrows(ApiException.class,()->f.apps.decide(d));
        f.sql("UPDATE merchant SET owner_user_id=710300");auth.logout(UUID.randomUUID().toString(),owner);code(CommonApiCodes.UNAUTHORIZED,()->f.apps.decide(d));
        f.bearer(buyer);auth.logout(UUID.randomUUID().toString(),buyer);code(CommonApiCodes.UNAUTHORIZED,()->f.apps.apply(c));
    }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}}

    @Test void bothOrdinarySourcesUseOriginalChannelAndOnlyFinalSuccessReleases()throws Exception{for(boolean verifiedOrder:List.of(false,true))for(boolean timeout:List.of(false,true))try(var f=new F()){
        String o=f.ready(verifiedOrder);var apply=f.applyCommand(o);var r=f.apps.apply(apply);Decide decision=timeout?null:f.decision(r,"APPROVE");Receipt d;
        if(timeout){f.source.fixed.set(OffsetDateTime.parse(r.merchantDeadline()).toInstant());f.apps.handle(f.timeout(r));d=f.receipt();}else d=f.apps.decide(decision);
        String refund=f.apps.createApproved(f.create(d));var runtime=f.runtime(true);
        assertThrows(ApiException.class,()->runtime.execution.execute(refund,"710302",false,"qa","LATE_PAYMENT_TIMEOUT"));
        assertThrows(ApiException.class,()->runtime.execution.execute(refund,"710302",false,"qa"));assertEquals(0,runtime.sends.get());
        assertTrue(runtime.execution.execute(refund,"710302",false,"qa","APPLICATION").done());assertEquals("UNKNOWN",f.text("SELECT status FROM refund_order"));
        assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));assertEquals(1,f.count("SELECT COUNT(*) FROM async_task WHERE task_type='APPLICATION_REFUND_CHANNEL_QUERY'"));
        assertTrue(runtime.execution.execute(refund,"710302",false,"qa","APPLICATION").done());assertEquals(1,runtime.sends.get());
        f.sql("UPDATE payment_refund_dispatch SET query_not_before=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
        assertTrue(runtime.execution.execute(refund,"710302",true,"qa","APPLICATION").done());assertEquals(1,runtime.sends.get());assertEquals(1,runtime.queries.get());
        assertEquals("SUCCESS",f.text("SELECT status FROM refund_order"));assertEquals("CONFIRMED",f.text("SELECT status FROM schedule_reservation"));
        String verified=f.text("SELECT CAST(verified_at AS CHAR) FROM pet_order");var event=f.event("RefundSucceededEvent.v1");
        f.fail("schedule_reservation:UPDATE");assertThrows(ApiException.class,()->runtime.projection.consume(event));f.unfail();
        assertEquals(0,f.count("SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='ORDER_APPLICATION_REFUND'"));assertEquals(0,f.count("SELECT refunded_amount FROM pet_order"));
        runtime.projection.consume(event);runtime.projection.consume(event);assertEquals("RELEASED",f.text("SELECT status FROM schedule_reservation"));
        assertEquals(128,f.count("SELECT refunded_amount FROM pet_order"));assertEquals(1,f.count("SELECT COUNT(*) FROM schedule_reservation_audit WHERE action='REFUND_RELEASE'"));
        assertEquals(verified,f.text("SELECT CAST(verified_at AS CHAR) FROM pet_order"));assertEquals(r,f.apps.apply(apply));if(decision!=null)assertEquals(d,f.apps.decide(decision));
        assertTrue(runtime.execution.execute(refund,"710302",true,"qa","APPLICATION").done());assertEquals(1,runtime.queries.get());
        new OrderLateRefundProjectionConsumer(f.source,IDS::incrementAndGet,f.guard,f.t.r.f.f.reservationExpiry,runtime.refunds).consume(event);
        new OrderMerchantRefundProjectionConsumer(f.source,IDS::incrementAndGet,f.guard,new OrderMerchantRejectFactsApiImpl(f.source,f.guard,f.reservations),runtime.refunds,runtime.release).consume(event);
    }}
    @Test void fabricatedApprovalOrCreatedBindingCannotReachChannel(){for(String change:List.of(
        "UPDATE refund_execution SET source_decision_id=source_decision_id+1",
        "UPDATE refund_application_decision SET operator_id=710101",
        "UPDATE refund_application_command SET canonical_bytes=X'00' WHERE command_namespace=CAST('refund.application.decide' AS BINARY)",
        "DELETE FROM order_refund_application_commit"))try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var d=f.apps.decide(f.decision(r,"APPROVE"));String refund=f.apps.createApproved(f.create(d));var runtime=f.runtime(false);f.sql(change);
        assertThrows(ApiException.class,()->runtime.execution.execute(refund,"710302",false,"qa","APPLICATION"));assertEquals(0,runtime.sends.get());assertEquals(0,f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
    }}
    @Test void forgedAndCrossTransactionCreateTokensHaveNoDurableEffects(){try(var f=new F()){
        var r=f.apps.apply(f.applyCommand(f.ready(true)));var d=f.apps.decide(f.decision(r,"APPROVE"));var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setIsolationLevel(2);
        var token=new AtomicReference<String>();assertThrows(ApiException.class,()->tx.executeWithoutResult(s->{var q=new QueryContext("qa",OperatorType.SYSTEM,null);f.guard.acquire(List.of("710302"),q);
            var approved=f.apps.requireApproved(r.applicationId(),d.decisionId(),"710302",q);
            token.set(f.orders.acquireCreate(r.applicationId(),d.decisionId(),r.orderId(),"710302",approved.commandId(),task("REFUND_APPLICATION_CREATE",r.applicationId()),f.source).token());
        }));assertNotNull(token.get());
        for(String value:List.of("forged",token.get()))assertThrows(ApiException.class,()->tx.executeWithoutResult(s->{f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));f.orders.commitCreated(value,r.orderId(),"710302","999",OffsetDateTime.now(ZoneOffset.UTC),f.source);}));
        assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(0,f.count("SELECT COUNT(*) FROM order_operation_guard WHERE operation_type='CREATE_REFUND'"));
    }}
    @Test void legacyUnverifiableApplicationStopsMigrationWithoutDeletingHistory()throws Exception{try(var db=new BookingCreateAcceptanceTest.Database(false)){
        db.jdbc.update("INSERT INTO refund_application(id,application_no,order_id,applicant_user_id,status,requested_amount,merchant_deadline,request_id,created_at,updated_at) VALUES(1,1,2,3,'PENDING_MERCHANT',128,UTC_TIMESTAMP(3)+INTERVAL 24 HOUR,'legacy',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->db.script("49-Refund-Application-Schema-v0.1.sql"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM refund_application WHERE id=1",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund_application' AND column_name='created_command_id'",Integer.class));
    }}

    static CommandContext ctx(String user){return new CommandContext(UUID.randomUUID().toString(),"refund-qa",OperatorType.USER,user,"MINIAPP");}
    static CommandContext task(String type,String app){return new CommandContext("TASK:"+type+":"+app,"refund-task-qa",OperatorType.SYSTEM,null,"ASYNC_TASK");}
    static void code(String expected,org.junit.jupiter.api.function.Executable work){assertEquals(expected,assertThrows(ApiException.class,work).code());}
    static final class F implements AutoCloseable {
        final VerificationCredentialAcceptanceTest.T t=new VerificationCredentialAcceptanceTest.T();
        final TimeSource source=new TimeSource(t.r.f.f.db.source);
        final ScheduleCapacityGuardApiImpl guard=new ScheduleCapacityGuardApiImpl(source);
        final PaymentSuccessFactsApiImpl payments=new PaymentSuccessFactsApiImpl(source,guard);
        final TransactionalOutboxPublisher outbox=new TransactionalOutboxPublisher(source,IDS::incrementAndGet,JSON);
        final AtomicReference<RefundApplicationApprovalFactsApi> approvals=new AtomicReference<>();
        final ScheduleProtectionFactsApiImpl schedule=new ScheduleProtectionFactsApiImpl(source,guard);
        final ReservationConfirmApiImpl reservations=new ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,schedule,new OrderPaymentFactsApiImpl(source,guard));
        final OrderRefundApplicationApiImpl orders=new OrderRefundApplicationApiImpl(source,guard,IDS::incrementAndGet,payments,new RefundOrderFactsApiImpl(source,guard),reservations,schedule,approvals::get);
        final AtomicBoolean moderationAllowed=new AtomicBoolean(true);
        final AtomicReference<RefundApplicationPorts.SessionAuthority> sessions=new AtomicReference<>(user->{if(!t.r.f.sessionActive.get())throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Session revoked");});
        RefundApplicationService apps;Decide lastDecision;
        F(){apps=build();}
        RefundApplicationService build(){var owner=new MerchantOrderAuthorityApiImpl(source,guard);var recovery=new JdbcAsyncTaskRecoverer(source,IDS::incrementAndGet);
            var service=new RefundApplicationService(source,IDS::incrementAndGet,guard,orders,payments,outbox,user->sessions.get().requireCurrent(user),
                (c,m,s)->owner.requireOwner(m,s,new QueryContext(c.traceId(),c.operatorType(),c.operatorId())),
                code->{if(!"QA_REASON".equals(code))throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"QA fixture only");},
                text->new RefundApplicationPorts.Approval(RefundApplicationService.sha(text.getBytes(StandardCharsets.UTF_8)),"qa-policy-1",moderationAllowed.get()),
                new RefundApplicationAesProtection(new byte[32]),spec->recovery.recover(spec.taskKey(),"REFUND",spec.taskType(),spec.bizType(),spec.bizId(),spec.expectedVersion(),spec.payloadJson(),spec.maxRetryCount(),spec.retryPolicy(),spec.availableAt()));approvals.set(service);return service;
        }
        String ready(boolean verified){String o=t.ready();if(verified){var c=t.issue(o,"INITIAL");VerificationCompletionAcceptanceTest.build(t).verify(VerificationCompletionAcceptanceTest.command(o,c));}
            else {sql("UPDATE pet_order SET appointment_start_at=DATE_SUB(appointment_start_at,INTERVAL 4 YEAR),appointment_end_at=DATE_SUB(appointment_end_at,INTERVAL 4 YEAR)");
                for(String table:List.of("schedule_reservation","schedule_reservation_claim","schedule_availability_window"))sql("UPDATE "+table+" SET start_at=DATE_SUB(start_at,INTERVAL 4 YEAR),end_at=DATE_SUB(end_at,INTERVAL 4 YEAR)");}return o;}
        Apply applyCommand(String order){return new Apply(ctx("710100"),order,"QA_REASON","private refund reason");}
        Decide decision(Receipt r,String action){lastDecision=decisionWithContext(r,action,ctx("710300"));return lastDecision;}
        Decide decisionWithContext(Receipt r,String action,CommandContext context){return new Decide(context,r.applicationId(),"0",action,"REJECT".equals(action)?"merchant private rejection":null);}
        Timeout timeout(Receipt r){return new Timeout(task("REFUND_MERCHANT_TIMEOUT",r.applicationId()),r.applicationId(),"710302",OffsetDateTime.parse(r.merchantDeadline()));}
        Create create(Receipt r){return new Create(task("REFUND_APPLICATION_CREATE",r.applicationId()),r.applicationId(),r.decisionId(),"710302");}
        Receipt receipt(){return t.r.f.f.db.jdbc.queryForObject("SELECT * FROM refund_application",(rs,n)->new Receipt(rs.getString("order_id"),rs.getString("id"),rs.getString("status"),rs.getString("version"),rs.getTimestamp("merchant_deadline").toInstant().atOffset(ZoneOffset.UTC).toString(),rs.getTimestamp("decided_at").toInstant().atOffset(ZoneOffset.UTC).toString(),rs.getString("decision_id")));}
        void fail(String operation){String[] p=operation.split(":");sql("CREATE TRIGGER qa_refund_failure BEFORE "+p[1]+" ON "+p[0]+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA injected refund failure'");}
        void unfail(){sql("DROP TRIGGER qa_refund_failure");}
        void sql(String s){t.sql(s);}long count(String s){return t.count(s);}String text(String s){return t.text(s);}
        String login(VerificationCompletionAcceptanceTest.SessionCache cache,String user)throws Exception{String token=UUID.randomUUID().toString();String hash=RefundApplicationService.sha(token.getBytes(StandardCharsets.UTF_8));cache.put("session:"+hash,JSON.writeValueAsString(Map.of("userId",Long.parseLong(user),"sessionId",IDS.incrementAndGet(),"expiresAtMs",System.currentTimeMillis()+60000)),Duration.ofMinutes(1));return token;}
        void bearer(String token){var request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));}
        DispatchedEvent event(String type){return t.r.f.event(type);}
        Runtime runtime(boolean unknown){return new Runtime(this,unknown);}
        public void close(){t.close();}
    }
    static final class Runtime {
        final AtomicInteger sends=new AtomicInteger(),queries=new AtomicInteger();final LateRefundService refunds;
        final RefundExecutionService execution;final ReservationRefundReleaseApiImpl release;final OrderApplicationRefundProjectionConsumer projection;
        Runtime(F f,boolean unknown){
            var lateOrders=new OrderLatePaymentFactsApiImpl(f.source,f.guard,f.t.r.f.f.reservationExpiry);
            var merchantOrders=new OrderMerchantRejectFactsApiImpl(f.source,f.guard,f.reservations);
            refunds=new LateRefundService(f.source,IDS::incrementAndGet,f.guard,lateOrders,f.payments,f.outbox,merchantOrders,false,f.orders,f.approvals::get);
            var channel=new PaymentRefundChannel(){
                public VerifiedResult submit(RefundRequest request){sends.incrementAndGet();if(unknown)throw new IllegalStateException("QA lost channel ACK");return success(request);}
                public VerifiedResult query(RefundRequest request){queries.incrementAndGet();return success(request);}
                private VerifiedResult success(RefundRequest r){return new VerifiedResult("SUCCESS",r.refundNo(),"QA_APPLICATION_REFUND",12800,12800L,f.source.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0),"d".repeat(64));}
            };
            var payment=new PaymentRefundService(f.source,IDS::incrementAndGet,f.guard,lateOrders,f.payments,refunds,channel,
                new PaymentRefundService.Settings("127.0.0.1","https://qa.invalid/refund",ZoneId.of("Asia/Shanghai")),Clock.fixed(f.source.instant(),ZoneOffset.UTC),merchantOrders,f.orders,f.apps);
            execution=new RefundExecutionService(refunds,payment,new PaymentRefundResultFactsApiImpl(f.source,f.guard));
            release=new ReservationRefundReleaseApiImpl(f.source,IDS::incrementAndGet,f.guard,refunds);
            projection=new OrderApplicationRefundProjectionConsumer(f.source,IDS::incrementAndGet,f.guard,f.orders,f.orders,refunds,release);
        }
    }
    static final class TimeSource extends DelegatingDataSource {
        final AtomicLong shiftSeconds=new AtomicLong();final AtomicReference<Instant> fixed=new AtomicReference<>();
        final AtomicInteger commitCalls=new AtomicInteger(),loseAtCommit=new AtomicInteger(-1),lostAcks=new AtomicInteger();
        Instant instant(){return fixed.get()==null?Instant.now().plusSeconds(shiftSeconds.get()):fixed.get();}
        TimeSource(DataSource target){super(target);}
        @Override public Connection getConnection()throws SQLException{return clock(super.getConnection());}
        @Override public Connection getConnection(String user,String pass)throws SQLException{return clock(super.getConnection(user,pass));}
        private Connection clock(Connection c)throws SQLException{Instant at=fixed.get();if(at==null&&shiftSeconds.get()!=0)at=Instant.now().plusSeconds(shiftSeconds.get());
            try(var statement=c.createStatement()){statement.execute("SET timestamp="+(at==null?"0":java.math.BigDecimal.valueOf(at.toEpochMilli(),3).toPlainString()));}
            return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{Object result=method.invoke(c,args);if(method.getName().equals("commit")&&commitCalls.incrementAndGet()==loseAtCommit.get()){lostAcks.incrementAndGet();throw new SQLException("QA committed refund ACK loss","08006");}return result;}
                catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });}
    }
}
