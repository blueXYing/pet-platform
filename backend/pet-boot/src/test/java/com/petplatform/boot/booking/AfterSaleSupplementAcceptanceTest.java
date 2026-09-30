package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi.SubmitEvidence;
import com.petplatform.aftersale.api.command.AfterSaleSupplementTimeoutApi.Timeout;
import com.petplatform.common.*;
import com.petplatform.task.core.AsyncTaskWorker;
import java.util.*;
import org.junit.jupiter.api.Test;

class AfterSaleSupplementAcceptanceTest {
    @Test void fabricatedSystemCommandCannotStandInForDurableWorkerDelivery() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var current=f.accept(f.create(f.verifiedOrder()));var deadline=f.now().plusMinutes(5);
            var waiting=f.requestEvidence(current,"USER",deadline);f.at(deadline.toInstant());
            var context=new CommandContext("TASK:AFTERSALE_SUPPLEMENT_TIMEOUT:"+waiting.supplementRequestId()+":0",
                    "TASK:1:1",OperatorType.SYSTEM,null,"ASYNC_TASK");
            assertThrows(ApiException.class,()->f.aftersales.handle(new Timeout(context,waiting.afterSaleId(),
                    waiting.supplementRequestId(),AfterSaleFixture.STORE,deadline.toString())));
            assertEquals("WAITING_SUPPLEMENT",f.caseView(waiting).status());
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED,f.runSupplementWorker());
            assertEquals("PROCESSING",f.caseView(waiting).status());
            assertEquals("TIMED_OUT",f.text("SELECT status FROM aftersale_supplement"));
        }
    }

    @Test void dueTaskRecoversAfterLossAndLateEvidenceDoesNotRewriteExpiredRound() throws Exception {
        for(String lost:List.of("MISSING","DEAD"))try(var f=new AfterSaleFixture()) {
            var current=f.accept(f.create(f.verifiedOrder()));var deadline=f.now().plusMinutes(5);
            var waiting=f.requestEvidence(current,"USER",deadline);f.at(deadline.toInstant());
            if(lost.equals("MISSING"))f.ordinary.sql("DELETE FROM async_task WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'");
            else f.ordinary.sql("UPDATE async_task SET status='DEAD',retry_count=8 WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'");
            f.aftersales.reconcileTasks();
            assertEquals("READY",f.text("SELECT status FROM async_task WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'"));
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED,f.runSupplementWorker());
            var view=f.caseView(waiting);assertEquals("PROCESSING",view.status());
            f.identity.asUser(AfterSaleFixture.BUYER);
            assertThrows(ApiException.class,()->f.aftersales.submitEvidence(new SubmitEvidence(f.user(AfterSaleFixture.BUYER),
                    waiting.afterSaleId(),view.version(),waiting.supplementRequestId(),"Late facts must not complete the expired supplement round",List.of())));
            var added=f.aftersales.submitEvidence(new SubmitEvidence(f.user(AfterSaleFixture.BUYER),waiting.afterSaleId(),
                    view.version(),null,"General new facts remain visible after the round has expired",List.of()));
            assertEquals("PROCESSING",added.status());
            assertEquals("TIMED_OUT",f.text("SELECT status FROM aftersale_supplement"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }

    @Test void completedSupplementMakesOldTaskAnIdempotentNoOp() throws Exception {
        try(var f=new AfterSaleFixture()) {
            var current=f.accept(f.create(f.verifiedOrder()));var deadline=f.now().plusMinutes(5);
            var waiting=f.requestEvidence(current,"USER",deadline);
            f.at(deadline.minusNanos(1_000_000).toInstant());f.identity.asUser(AfterSaleFixture.BUYER);
            var answered=f.aftersales.submitEvidence(new SubmitEvidence(f.user(AfterSaleFixture.BUYER),waiting.afterSaleId(),
                    waiting.version(),waiting.supplementRequestId(),"Timely facts satisfy this exact requested supplement round",List.of()));
            f.at(deadline.toInstant());
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED,f.runSupplementWorker());
            assertEquals(answered.version(),f.caseView(answered).version());
            assertEquals("SUBMITTED",f.text("SELECT status FROM aftersale_supplement"));
        }
    }

    @Test void realVerificationCancelsOpenSupplementAndNeverResurrectsItsTimeout() throws Exception {
        try(var f=new AfterSaleFixture()) {
            String order=f.rejectedOrder();var current=f.accept(f.create(order));var deadline=f.now().plusMinutes(5);
            var waiting=f.requestEvidence(current,"USER",deadline);
            var verified=f.verify(f.verificationCommand(order));
            assertEquals("VERIFIED",verified.resultCode());
            var invalidated=f.caseView(waiting);assertEquals("INVALIDATED",invalidated.status());
            assertNull(invalidated.supplementRequestId());
            assertEquals("CANCELED",f.text("SELECT status FROM aftersale_supplement"));
            assertEquals(verified.verificationId(),f.text("SELECT CAST(completion_verification_id AS CHAR) FROM aftersale_supplement"));
            assertNull(f.text("SELECT CAST(completion_command_id AS CHAR) FROM aftersale_supplement"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM aftersale_supplement s JOIN aftersale_verification_proof p ON p.verification_id=s.completion_verification_id AND p.aftersale_id=s.aftersale_id AND p.verified_at=s.closed_at WHERE p.invalidated=1"));
            f.at(deadline.toInstant());
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED,f.runSupplementWorker());
            assertEquals(invalidated.version(),f.caseView(waiting).version());
            assertEquals("SUCCEEDED",f.text("SELECT status FROM async_task WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'"));
            assertEquals(0,f.aftersales.reconcileTasks());assertEquals(0,f.aftersales.reconcileTasks());
            assertEquals("SUCCEEDED",f.text("SELECT status FROM async_task WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'"));
            // A missing obsolete task also stays missing; recovery must not revive a canceled round.
            f.ordinary.sql("DELETE FROM async_task WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'");
            assertEquals(0,f.aftersales.reconcileTasks());
            assertEquals(0,f.count("SELECT COUNT(*) FROM async_task WHERE task_type='AFTERSALE_SUPPLEMENT_TIMEOUT'"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM aftersale_recovery_issue WHERE status='OPEN'"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
}
