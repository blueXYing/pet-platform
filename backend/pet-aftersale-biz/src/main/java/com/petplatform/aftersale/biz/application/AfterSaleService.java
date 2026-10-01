package com.petplatform.aftersale.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.petplatform.aftersale.api.command.*;
import com.petplatform.aftersale.api.query.*;
import com.petplatform.aftersale.biz.infrastructure.persistence.AfterSaleWorkflowStore;
import com.petplatform.aftersale.biz.infrastructure.persistence.mapper.AfterSaleWorkflowMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.order.api.command.OrderAfterSaleCommitApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi;
import com.petplatform.payment.api.query.RefundFundingEvidenceChecks;
import com.petplatform.refund.api.command.RefundAfterSaleCommandApi;
import com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.petplatform.aftersale.api.command.AfterSaleCommandApi.*;
import static com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import static com.petplatform.aftersale.api.query.AfterSaleRefundFactsApi.*;
import static com.petplatform.aftersale.biz.application.AfterSalePorts.*;

/** AFS owns workflow, immutable evidence and final decisions. All external facts come through public APIs. */
public final class AfterSaleService implements AfterSaleCommandApi, AfterSaleQueryApi,
        AfterSaleCaseFactsApi, AfterSaleRefundFactsApi, AfterSaleSupplementTimeoutApi, AfterSaleEvidenceAccessApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Set<String> LIVE=Set.of("PENDING","PROCESSING","WAITING_SUPPLEMENT");
    private static final Set<String> ENDED=Set.of("RESOLVED","INVALIDATED","WITHDRAWN","CLOSED");
    private static final Set<String> NON_REFUND=Set.of("REJECT","RESERVICE","OTHER");
    private final DataSource source; private final TransactionTemplate tx; private final AfterSaleWorkflowMapper db;
    private final ScheduleCapacityGuardApi guard; private final SnowflakeIdGenerator ids; private final IntegrationEventPublisher events;
    private final OrderAfterSaleFactsApi orders; private final OrderAfterSaleCommitApi commits;
    private final RefundApplicationHistoryFactsApi history; private final RefundAfterSaleCommandApi refunds;
    private final RefundFundingEligibilityFactsApi funding; private final Authority authority; private final ReasonPolicy reasons;
    private final Moderation moderation; private final Protection protection; private final Assets assets; private final Tasks tasks;
    private final Map<Object,Map<Long,String>> liveCommands=new ConcurrentHashMap<>(); private long scanAfter;

    public AfterSaleService(DataSource source, ScheduleCapacityGuardApi guard, SnowflakeIdGenerator ids,
            IntegrationEventPublisher events, OrderAfterSaleFactsApi orders, OrderAfterSaleCommitApi commits,
            RefundApplicationHistoryFactsApi history, RefundAfterSaleCommandApi refunds,
            RefundFundingEligibilityFactsApi funding, Authority authority, ReasonPolicy reasons,
            Moderation moderation, Protection protection, Assets assets, Tasks tasks) {
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);this.ids=Objects.requireNonNull(ids);
        this.events=Objects.requireNonNull(events);this.orders=Objects.requireNonNull(orders);this.commits=Objects.requireNonNull(commits);
        this.history=Objects.requireNonNull(history);this.refunds=Objects.requireNonNull(refunds);this.funding=Objects.requireNonNull(funding);
        this.authority=Objects.requireNonNull(authority);this.reasons=Objects.requireNonNull(reasons);this.moderation=Objects.requireNonNull(moderation);
        this.protection=Objects.requireNonNull(protection);this.assets=Objects.requireNonNull(assets);this.tasks=Objects.requireNonNull(tasks);
        db=AfterSaleWorkflowStore.mapper(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public Receipt create(Create c) {return createWithOutcome(c).receipt();}
    @Override public CreationResult createWithOutcome(Create c) {return safe(()->{
        if(c==null)throw invalid();user(c.context());id(c.orderId());text(c.description(),10,500);text(c.typeCode(),1,64);text(c.demandCode(),1,64);
        if(c.newProblemStatement()!=null)text(c.newProblemStatement(),10,500);assetIds(c.evidenceAssetIds());optionalAmount(c.requestedAmount());
        top();authority.requireUser(c.context());
        var input=json(values("orderId",c.orderId(),"typeCode",c.typeCode(),"demandCode",c.demandCode(),"description",c.description(),"requestedAmount",money(c.requestedAmount()),"assets",sorted(c.evidenceAssetIds()),"newProblemStatement",c.newProblemStatement()));
        var key=key("aftersale.create",c.context(),"ORDER:"+c.orderId());var purpose=purpose(key);var saved=committed(key,purpose,input,c.context());if(saved!=null)return new CreationResult(saved,false);
        AfterSaleCatalog.requireCodes(reasons.options(),c.typeCode(),c.demandCode());reasons.requireCodes(c.typeCode(),c.demandCode());var moderationProof=moderate(c.description());moderate(c.newProblemStatement());admit(key,purpose,input);
        return tx.execute(s->{defaults();var b=db.binding(key);same(b,purpose,input);var loc=orders.locate(c.orderId(),system(c.context()));
            guard.acquire(List.of(loc.storeId()),system(c.context()));authority.requireUser(c.context());if(!c.context().operatorId().equals(loc.userId()))throw forbidden();
            if("SUCCEEDED".equals(b.state))return new CreationResult(replay(b,purpose,c.context()),false);reserved(b);activate(b.id,"");
            var fact=orderFacts(c.orderId(),loc.storeId(),system(c.context()));var hf=history.readForOrder(c.orderId(),loc.storeId(),system(c.context()),source);
            var at=now();var eligibility=eligibility(fact,hf,at);if(!eligibility.eligible())throw error(eligibility.blockingReason());
            var prior=finalCases(id(c.orderId()));if(!prior.isEmpty()&&(c.newProblemStatement()==null||c.newProblemStatement().isBlank()))throw invalid();
            if(c.requestedAmount()!=null&&c.requestedAmount().compareTo(fact.payment().paidAmount())>0)throw error("AFTERSALE_AMOUNT_INVALID");
            var owned=resolveAssets(c.context().operatorId(),c.evidenceAssetIds());long caseId=next(),event=next();
            OffsetDateTime anchor="VERIFIED".equals(eligibility.sourceStage())?fact.verifiedAt():fact.appointmentStart();
            var origin=new Origin(fact,hf.latestRejected(),prior);var content=new Content(c.description(),c.newProblemStatement());
            var v=values("case",caseId,"order",id(c.orderId()),"user",id(loc.userId()),"merchant",id(loc.merchantId()),"store",id(loc.storeId()),"stage",eligibility.sourceStage(),"amount",c.requestedAmount(),"at",utc(at),"command",b.id,"event",event,"city",fact.cityCode(),"scopeVersion",fact.scopeVersion(),"anchor",utc(anchor),"deadline",utc(anchor.plusDays(7)),"origin",encrypt("CASE_ORIGIN:"+caseId,origin),"content",encrypt("CASE_CONTENT:"+caseId,content),"type",c.typeCode(),"demand",c.demandCode(),"finalVersion",finalVersion(prior));
            one(db.create(v));var row=caseRow(caseId);long batch=insertBatch(row,b.id,"USER",c.context(),null,c.description(),null,owned,moderationProof,at);
            var receipt=new Receipt(str(b.id),c.orderId(),str(caseId),"PENDING","0",time(at),str(batch),null,null,null);
            record(row,b,c.context(),null,"CREATED",event,receipt,null);commits.bindCreated(c.orderId(),loc.storeId(),str(caseId),fact.orderVersion(),c.context(),source);
            publish(event,"AfterSaleCreatedEvent",str(caseId),at,c.context(),values("afterSaleId",str(caseId),"orderId",c.orderId(),"userId",loc.userId(),"afterSaleType",c.typeCode(),"createdAt",time(at)));
            finish(b,purpose,receipt);before(()->{authority.requireUser(c.context());requireOrderCurrent(row);});return new CreationResult(receipt,true);
        });
    });}

    @Override public Receipt accept(Accept c) {
        if(c==null)throw invalid();if(c.newProblemAssessment()!=null)text(c.newProblemAssessment(),1,500);
        if(c.expectedFinalSetVersion()!=null&&!c.expectedFinalSetVersion().matches("[0-9a-f]{64}"))throw invalid();
        return change("accept",c.context(),c.afterSaleId(),c.expectedVersion(),values("assessment",c.newProblemAssessment(),"finalSetVersion",c.expectedFinalSetVersion()),()->moderate(c.newProblemAssessment()),st->{
            st.admin("aftersale.handle");st.require("PENDING");var prior=finalCases(st.row.orderId);
            if(!prior.isEmpty()){
                text(c.newProblemAssessment(),1,500);if(c.newProblemAssessment().isBlank()||c.expectedFinalSetVersion()==null)throw invalid();
                if(!finalVersion(prior).equals(c.expectedFinalSetVersion())){st.finalAuthority.run();throw error("AFTERSALE_FINAL_SET_CONFLICT");}
            }
            st.to="PROCESSING";st.action="ACCEPTED";st.detail=values("assessment",c.newProblemAssessment(),"finalCaseIds",prior,"finalSetVersion",finalVersion(prior));
        });
    }
    @Override public Receipt requestSupplement(RequestSupplement c) {
        if(c==null)throw invalid();text(c.reason(),1,500);if(c.reason().isBlank()||c.targetParty()==null||!Set.of("USER","MERCHANT").contains(c.targetParty()))throw invalid();var deadline=parseTime(c.deadline());
        return change("supplement.request",c.context(),c.afterSaleId(),c.expectedVersion(),values("target",c.targetParty(),"reason",c.reason(),"deadline",time(deadline)),()->moderate(c.reason()),st->{
            st.admin("aftersale.handle");st.require("PROCESSING");if(!deadline.isAfter(st.at)||!db.openSupplements(st.row.id).isEmpty())throw invalid();
            long supplement=next();one(db.insertSupplement(values("supplement",supplement,"case",st.row.id,"command",st.binding.id,"target",c.targetParty(),"reason",encrypt("SUPPLEMENT:"+supplement,c.reason()),"deadline",utc(deadline),"at",utc(st.at))));
            st.supplement=supplement;st.to="WAITING_SUPPLEMENT";st.action="SUPPLEMENT_REQUESTED";tasks.enqueue(taskSpec(st.row,db.supplement(supplement)));
        });
    }
    @Override public Receipt submitEvidence(SubmitEvidence c) {
        if(c==null)throw invalid();return evidence(c.context(),c.afterSaleId(),c.expectedVersion(),c.supplementRequestId(),c.text(),null,c.evidenceAssetIds(),false,null);
    }
    @Override public Receipt submitEvidence(SubmitEvidence c,RouteParty routeParty) {
        if(c==null||routeParty==null)throw invalid();if(routeParty==RouteParty.OPS)throw forbidden();
        return evidence(c.context(),c.afterSaleId(),c.expectedVersion(),c.supplementRequestId(),c.text(),null,c.evidenceAssetIds(),false,routeParty);
    }
    @Override public Receipt submitMerchantOpinion(SubmitMerchantOpinion c) {
        if(c==null||c.opinionCode()==null||!Set.of("AGREE","PARTLY_AGREE","DISAGREE","NEED_USER_SUPPLEMENT").contains(c.opinionCode()))throw invalid();
        text(c.explanation(),10,500);return evidence(c.context(),c.afterSaleId(),c.expectedVersion(),c.supplementRequestId(),c.explanation(),c.opinionCode(),c.evidenceAssetIds(),true,null);
    }
    private Receipt evidence(CommandContext context,String caseId,String version,String supplement,String text,String opinion,List<String> assetIds,boolean merchantOnly,RouteParty routeParty) {
        user(context);assetIds(assetIds);if(text!=null)text(text,10,500);if((text==null||text.isBlank())&&assetIds.isEmpty())throw invalid();
        var parameters=values("supplementRequestId",supplement,"text",text,"opinion",opinion,"assets",sorted(assetIds));if(routeParty!=null)parameters.put("routeParty",routeParty.name());
        return change(merchantOnly?"opinion.submit":"evidence.submit",context,caseId,version,parameters,()->moderate(text),st->{
            authority.requireUser(context);String party;
            if(routeParty!=null){selectedRead(context,st.row,routeParty);party=routeParty.name();if(routeParty==RouteParty.MERCHANT)authority.requireOwner(context,resource(st.row));}
            else if(context.operatorId().equals(str(st.row.userId))&&!merchantOnly)party="USER";else{authority.requireOwner(context,resource(st.row));party="MERCHANT";}
            st.finalAuthority=()->{authority.requireUser(context);if(routeParty!=null)selectedRead(context,st.row,routeParty);if("MERCHANT".equals(party))authority.requireOwner(context,resource(st.row));};
            if(!LIVE.contains(st.row.status))throw state();var owned=resolveAssets(context.operatorId(),assetIds);Long matching=null;
            if("WAITING_SUPPLEMENT".equals(st.row.status)){
                var round=currentSupplement(st.row);if(supplement==null||!str(round.id).equals(supplement))throw error("AFTERSALE_SUPPLEMENT_STALE");
                if(!st.at.isBefore(offset(round.deadline)))throw error("AFTERSALE_SUPPLEMENT_EXPIRED");
                if(round.targetParty.equals(party)){matching=round.id;closeRound(st.row,round,"SUBMITTED",st.binding.id,st.at);st.supplement=null;st.affectedRound=round.id;st.to="PROCESSING";}
            }else if(supplement!=null)throw error("AFTERSALE_SUPPLEMENT_STALE");
            st.batch=insertBatch(st.row,st.binding.id,party,context,matching,text,opinion,owned,st.approval,st.at);st.action=merchantOnly?"MERCHANT_OPINION_ADDED":"EVIDENCE_ADDED";
        });
    }
    @Override public Receipt withdraw(Withdraw c) {
        if(c==null)throw invalid();return change("withdraw",c.context(),c.afterSaleId(),c.expectedVersion(),Map.of(),st->{
            user(c.context());authority.requireUser(c.context());if(!c.context().operatorId().equals(str(st.row.userId)))throw forbidden();
            st.finalAuthority=()->{authority.requireUser(c.context());if(!c.context().operatorId().equals(str(st.row.userId)))throw forbidden();};
            if(!LIVE.contains(st.row.status))throw state();st.cancelRound();st.to="WITHDRAWN";st.action="WITHDRAWN";
        });
    }
    @Override public Receipt closeDuplicate(CloseDuplicate c) {
        if(c==null)throw invalid();id(c.priorFinalCaseId());text(c.reason(),1,500);if(c.reason().isBlank())throw invalid();
        return change("duplicate.close",c.context(),c.afterSaleId(),c.expectedVersion(),values("priorFinalCaseId",c.priorFinalCaseId(),"reason",c.reason()),()->moderate(c.reason()),st->{
            st.admin("aftersale.handle");st.require("PENDING");var old=caseRow(id(c.priorFinalCaseId()));if(!old.orderId.equals(st.row.orderId)||!"RESOLVED".equals(old.status)||!NON_REFUND.contains(old.decisionType)||old.decisionId==null)throw invalid();
            decisionProof(old.decisionId);st.duplicate=old.id;st.to="CLOSED";st.action="DUPLICATE_CLOSED";st.detail=values("priorFinalCaseId",c.priorFinalCaseId(),"reasonCode","DUPLICATE_FINAL_PROBLEM","reason",c.reason());
        });
    }
    @Override public Receipt decide(Decide c) {
        if(c==null||c.decisionType()==null)throw invalid();text(c.reason(),1,500);if(c.reason().isBlank())throw invalid();boolean isRefund=Set.of("FULL_REFUND","PARTIAL_REFUND").contains(c.decisionType());
        if(!isRefund&&!NON_REFUND.contains(c.decisionType()))throw invalid();if(isRefund){optionalAmount(c.refundAmount());if(c.refundAmount()==null||c.refundAmount().signum()<=0)throw error("AFTERSALE_AMOUNT_INVALID");}else if(c.refundAmount()!=null)throw error("AFTERSALE_AMOUNT_INVALID");
        return change("decide",c.context(),c.afterSaleId(),c.expectedVersion(),values("decisionType",c.decisionType(),"refundAmount",money(c.refundAmount()),"reason",c.reason()),()->moderate(c.reason()),st->{
            var authz=st.admin("aftersale.decide");st.require("PROCESSING");if(st.row.decisionId!=null)throw error("AFTERSALE_DECISION_FINAL");
            var origin=origin(st.row);long decision=next();String token=null;var payment=origin.fact().payment();RefundFundingEligibilityFactsApi.FundingEvidence fundingProof=null;
            if(isRefund){
                String refundType="FULL_REFUND".equals(c.decisionType())?"FULL":"PARTIAL";
                try{RefundFundingEvidenceChecks.amount(refundType,c.refundAmount(),payment.paidAmount());}catch(ApiException failure){throw error("AFTERSALE_AMOUNT_INVALID");}
                var permit=commits.acquireRefund(str(st.row.orderId),str(st.row.storeId),str(st.row.id),str(st.row.version),str(decision),str(st.binding.id),c.context(),source);token=permit.token();
                if(!payment.equals(permit.fact().payment())||!st.row.sourceStage.equals(permit.sourceStage()))throw bad();
                var check=fundingCheck(st.row,origin,str(decision),str(st.binding.id),refundType,c.refundAmount());fundingProof=funding.requireForDecision(check,system(c.context()));st.at=now();RefundFundingEvidenceChecks.requireAllowed(check,fundingProof,st.at);
                activate(st.binding.id,token);
            }
            var proof=new DecisionFact(str(st.row.id),str(st.row.orderId),str(st.row.userId),str(st.row.merchantId),str(st.row.storeId),origin.fact().location().reservationId(),st.row.sourceStage,str(st.row.version),str(decision),c.decisionType(),isRefund?("FULL_REFUND".equals(c.decisionType())?"FULL":"PARTIAL"):null,c.refundAmount(),c.context().operatorId(),str(st.binding.id),str(st.event),st.at,payment,fundingProof);
            one(db.insertDecision(values("decision",decision,"case",st.row.id,"order",st.row.orderId,"store",st.row.storeId,"command",st.binding.id,"event",st.event,"actor",id(c.context().operatorId()),"type",c.decisionType(),"amount",c.refundAmount(),"paid",payment.paidAmount(),"version",st.row.version,"reason",encrypt("DECISION_REASON:"+decision,c.reason()),"authz",authz.authzVersion(),"scope",authz.scopeVersion(),"proof",encrypt("DECISION_PROOF:"+decision,new DecisionEnvelope(proof,authz.authzVersion(),authz.scopeVersion())),"tokenHash",token==null?null:sha(bytes(token)),"at",utc(st.at))));
            st.decision=decision;st.decisionType=c.decisionType();st.decisionAmount=c.refundAmount();st.to="RESOLVED";st.action="RESOLVED";st.orderToken=token;
            if(isRefund){var result=refunds.create(new RefundAfterSaleCommandApi.Create(c.context(),str(st.row.orderId),str(st.row.storeId),str(st.row.id),str(decision),token),source);
                if(result==null||!proof.refundType().equals(result.refundType())||!equal(proof.refundAmount(),result.refundAmount())||!equal(payment.paidAmount(),result.originalPaidAmount()))throw bad();
                st.refund=id(result.refundOrderId());st.refundCreatedAt=result.createdAt();one(db.bindRefund(values("refund",st.refund,"refundNo",id(result.refundNo()),"event",id(result.createdEventId()),"at",utc(result.createdAt()),"decision",decision,"case",st.row.id)));
            }
        });
    }
    @FunctionalInterface private interface Work { void run(State state); }
    private final class State {
        final AfterSaleWorkflowMapper.CaseRow row; final AfterSaleWorkflowMapper.Binding binding; final CommandContext context;
        final long event; OffsetDateTime at; String to,action,decisionType,orderToken; BigDecimal decisionAmount;
        Long supplement,batch,decision,refund,duplicate,affectedRound; Object detail; OffsetDateTime refundCreatedAt; Runnable finalAuthority; Approval approval;
        State(AfterSaleWorkflowMapper.CaseRow row,AfterSaleWorkflowMapper.Binding binding,CommandContext context){this.row=row;this.binding=binding;this.context=context;event=next();at=now();to=row.status;supplement=row.currentSupplementId;}
        void require(String expected){if(!expected.equals(row.status))throw state();}
        AdminAuthority admin(String action){if(context.operatorType()!=OperatorType.PLATFORM_OPERATOR)throw forbidden();var granted=authority.requireAdmin(context,resource(row),action);if(granted==null)throw bad();text(granted.authzVersion(),1,128);text(granted.scopeVersion(),1,128);finalAuthority=()->{if(!granted.equals(authority.requireAdmin(context,resource(row),action)))throw error(CommonApiCodes.CONFLICT);};return granted;}
        void cancelRound(){if(row.currentSupplementId!=null){var round=currentSupplement(row);closeRound(row,round,"CANCELED",binding.id,at);affectedRound=round.id;supplement=null;}}
    }
    private Receipt change(String namespace,CommandContext context,String caseId,String expectedVersion,Map<String,Object> parameters,Work work){return change(namespace,context,caseId,expectedVersion,parameters,()->null,work);}
    private Receipt change(String namespace,CommandContext context,String caseId,String expectedVersion,Map<String,Object> parameters,Supplier<Approval> prepare,Work work){return safe(()->{
        command(context);id(caseId);long expected=version(expectedVersion);top();var inputValues=new LinkedHashMap<String,Object>();inputValues.put("afterSaleId",caseId);inputValues.put("version",expectedVersion);inputValues.putAll(parameters);
        byte[] input=json(inputValues);var key=key("aftersale."+namespace,context,"AFTERSALE:"+caseId);String purpose=purpose(key);var saved=committed(key,purpose,input,context);if(saved!=null)return saved;var approval=prepare.get();admit(key,purpose,input);
        return tx.execute(s->{defaults();var b=db.binding(key);same(b,purpose,input);var hint=hint(id(caseId));guard.acquire(List.of(str(hint.storeId)),system(context));var row=caseRow(hint.id);
            authority.requireRead(context,resource(row));if("SUCCEEDED".equals(b.state))return replay(b,purpose,context);reserved(b);activate(b.id,"");
            var rejectedAuthority=commandAuthority(namespace,context,row,parameters);
            if(!LIVE.contains(row.status))throw "RESOLVED".equals(row.status)?error("AFTERSALE_DECISION_FINAL"):"INVALIDATED".equals(row.status)?error("AFTERSALE_ALREADY_INVALIDATED"):state();
            if(row.version!=expected){rejectedAuthority.run();throw error("AFTERSALE_VERSION_CONFLICT");}requireOrderCurrent(row);origin(row);
            var st=new State(row,b,context);st.approval=approval;work.run(st);return complete(st,purpose);
        });
    });}
    /** A definitive comparison error never replaces a current write authorization failure. */
    private Runnable commandAuthority(String namespace,CommandContext context,AfterSaleWorkflowMapper.CaseRow row,Map<String,Object> parameters){
        String action=switch(namespace){case "accept","supplement.request","duplicate.close"->"aftersale.handle";case "decide"->"aftersale.decide";default->null;};
        if(action!=null){
            var granted=authority.requireAdmin(context,resource(row),action);if(granted==null)throw bad();
            return ()->{if(!granted.equals(authority.requireAdmin(context,resource(row),action)))throw error(CommonApiCodes.CONFLICT);};
        }
        Runnable current=()->{
            authority.requireUser(context);
            if("withdraw".equals(namespace)){if(!context.operatorId().equals(str(row.userId)))throw forbidden();}
            else if("opinion.submit".equals(namespace))authority.requireOwner(context,resource(row));
            else if("evidence.submit".equals(namespace)){
                Object route=parameters.get("routeParty");
                if(route!=null){var party=RouteParty.valueOf(route.toString());selectedRead(context,row,party);if(party==RouteParty.MERCHANT)authority.requireOwner(context,resource(row));}
                else if(!context.operatorId().equals(str(row.userId)))authority.requireOwner(context,resource(row));
            }else throw bad();
        };
        current.run();return current;
    }
    private Receipt complete(State st,String purpose){
        long newVersion=Math.addExact(st.row.version,1);boolean active=LIVE.contains(st.to);
        one(db.transition(values("case",st.row.id,"oldVersion",st.row.version,"from",st.row.status,"to",st.to,"active",active?1:0,"supplement",st.supplement,"decision",st.decision,"refund",st.refund,"duplicate",st.duplicate,"decisionType",st.decisionType,"decisionAmount",st.decisionAmount,"action",st.action,"at",utc(st.at))));
        var row=caseRow(st.row.id);Long receiptRound=st.affectedRound!=null?st.affectedRound:st.supplement;
        var receipt=new Receipt(str(st.binding.id),str(row.orderId),str(row.id),row.status,str(newVersion),time(st.at),nullable(st.batch),nullable(receiptRound),nullable(st.decision),nullable(st.refund));
        record(row,st.binding,st.context,st.row.status,st.action,st.event,receipt,st.detail);
        if(st.orderToken==null)commits.projectTransition(str(row.orderId),str(row.storeId),str(row.id),str(row.version),st.context,source);
        else commits.commitCreated(st.orderToken,str(row.id),str(st.decision),str(st.refund),st.refundCreatedAt,source);
        if("RESOLVED".equals(st.action))publish(st.event,"AfterSaleResolvedEvent",str(row.id),st.at,st.context,values("afterSaleId",str(row.id),"orderId",str(row.orderId),"decisionType",st.decisionType,"decisionRefundAmount",st.decisionAmount,"decidedAt",time(st.at)));
        else {
            var round=receiptRound==null?null:db.supplement(receiptRound);
            publish(st.event,"AfterSaleProgressChangedEvent",str(row.id),st.at,st.context,values("afterSaleId",str(row.id),"orderId",str(row.orderId),"action",st.action,"fromStatus",st.row.status,"toStatus",row.status,"caseVersion",str(row.version),"supplementRequestId",nullable(receiptRound),"targetParty",round==null?null:round.targetParty,"deadline",round==null?null:time(offset(round.deadline)),"occurredAt",time(st.at)));
        }
        finish(st.binding,purpose,receipt);before(()->{
            if(st.context.operatorType()!=OperatorType.SYSTEM)authority.requireRead(st.context,resource(row));if(st.finalAuthority!=null)st.finalAuthority.run();requireOrderCurrent(row);
            if(st.refund!=null){requireCreated(str(row.id),str(st.decision),str(st.refund),str(row.storeId),system(st.context),source);commits.requireCreated(str(row.orderId),str(row.storeId),str(row.id),str(st.decision),str(st.refund),source);}
        });return receipt;
    }
    private void record(AfterSaleWorkflowMapper.CaseRow row,AfterSaleWorkflowMapper.Binding binding,CommandContext c,String from,String action,long event,Receipt receipt,Object detail){
        var v=values("command",binding.id,"case",row.id,"order",row.orderId,"from",from,"to",row.status,"version",row.version,"action",action,"event",event,"actorType",c.operatorType().name(),"actor",c.operatorId()==null?null:id(c.operatorId()),"at",utc(parseTime(receipt.occurredAt())),"batch",optionalId(receipt.evidenceBatchId()),"supplement",optionalId(receipt.supplementRequestId()),"decision",optionalId(receipt.decisionId()),"refund",optionalId(receipt.refundOrderId()),"detail",detail==null?null:encrypt("TRANSITION:"+binding.id,detail),"log",next());one(db.insertTransition(v));one(db.log(v));
    }

    @Override public Options options(CommandContext context){return safe(()->{
        queryActor(context,RouteParty.USER);top();String access=authorityVersion(authority.requireBuyerRead(context));
        var result=AfterSaleCatalog.requireValid(reasons.options());
        if(!access.equals(authorityVersion(authority.requireBuyerRead(context))))throw forbidden();return result;
    });}
    @Override public Eligibility checkEligibility(CommandContext context,String orderId){return safe(()->{
        id(orderId);queryActor(context,RouteParty.USER);top();String access=authorityVersion(authority.requireBuyerRead(context));Eligibility result;
        try{result=tx.execute(s->{defaults();var loc=orders.locate(orderId,system(context));guard.acquire(List.of(loc.storeId()),system(context));if(!access.equals(authorityVersion(authority.requireBuyerRead(context))))throw forbidden();if(!loc.userId().equals(context.operatorId()))throw forbidden();
            var f=orderFacts(orderId,loc.storeId(),system(context));return eligibility(f,history.readForOrder(orderId,loc.storeId(),system(context),source),now());});
        }catch(ApiException failure){if(Set.of("AFTERSALE_NOT_ELIGIBLE","REFUND_ORDER_ALREADY_EXISTS").contains(failure.code()))result=new Eligibility(false,null,null,failure.code(),null);else throw failure;}
        if(!access.equals(authorityVersion(authority.requireBuyerRead(context))))throw forbidden();return result;
    });}
    private OrderAfterSaleFactsApi.Fact orderFacts(String order,String store,QueryContext context){try{return orders.requireCurrentEligible(order,store,context,source);}catch(ApiException failure){if(Set.of("REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED","REFUND_NOT_ELIGIBLE").contains(failure.code()))throw error("AFTERSALE_NOT_ELIGIBLE");throw failure;}}
    private Eligibility eligibility(OrderAfterSaleFactsApi.Fact fact,RefundApplicationHistoryFactsApi.Fact h,OffsetDateTime at){
        var loc=fact.location();if(h==null||!loc.orderId().equals(h.orderId())||!loc.userId().equals(h.userId())||!loc.merchantId().equals(h.merchantId())||!loc.storeId().equals(h.storeId()))throw bad();
        var current=requireCurrent(loc.orderId(),loc.storeId(),fact.currentCaseId(),new QueryContext("aftersale-eligibility",OperatorType.SYSTEM,null),source);
        return AfterSaleEligibilityPolicy.evaluate(fact,h,current,at);
    }
    @Override public CaseView getCase(CommandContext context,String caseId){return caseView(context,caseId,null);}
    @Override public CaseView getCase(CommandContext context,String caseId,RouteParty routeParty){if(routeParty==null)throw invalid();return caseView(context,caseId,routeParty);}
    private CaseView caseView(CommandContext context,String caseId,RouteParty routeParty){return safe(()->{
        id(caseId);queryActor(context,routeParty);top();return tx.execute(s->{defaults();var hint=hint(id(caseId));guard.acquire(List.of(str(hint.storeId)),system(context));var row=caseRow(hint.id);var access=selectedRead(context,row,routeParty);var origin=origin(row);var content=decode("CASE_CONTENT:"+row.id,row.contentCipher,Content.class);
            var round=row.currentSupplementId==null?null:round(row,row.currentSupplementId);var prior=finalCases(row.orderId);var batches=new ArrayList<EvidenceBatch>();
            for(var b:db.batches(row.id)){var body=batchBody(b);batches.add(new EvidenceBatch(str(b.id),b.submitterType,body.text(),body.opinionCode(),time(offset(b.createdAt)),db.assets(b.id).stream().map(a->str(a.assetId)).toList()));}
            var decision=row.decisionId==null?null:decisionProof(row.decisionId);String reason=row.decisionId==null?null:decode("DECISION_REASON:"+row.decisionId,db.decision(row.decisionId).reasonCipher,String.class);
            before(()->{if(!access.equals(selectedRead(context,row,routeParty)))throw forbidden();});
            return new CaseView(str(row.id),str(row.orderId),row.status,str(row.version),row.sourceStage,row.typeCode,row.demandCode,content.description(),row.requestedAmount,time(offset(row.createdAt)),time(offset(row.eligibilityDeadline)),nullable(row.currentSupplementId),round==null?null:round.targetParty,round==null?null:time(offset(round.deadline)),round==null?null:decode("SUPPLEMENT:"+round.id,round.reasonCipher,String.class),finalVersion(prior),prior,content.newProblemStatement(),decision==null?null:decision.decisionType(),decision==null?null:decision.refundAmount(),reason,batches);
        });
    });}
    @Override public CasePage listMine(CommandContext context,ListQuery query){return safe(()->{
        listQuery(query);queryActor(context,RouteParty.USER);top();
        return tx.execute(s->{defaults();String access=authorityVersion(authority.requireBuyerRead(context));
            var filters=listFilters(query);filters.put("user",id(context.operatorId()));
            var result=casePage(query,filters);
            before(()->{if(!access.equals(authorityVersion(authority.requireBuyerRead(context))))throw forbidden();});return result;
        });
    });}
    @Override public CasePage listForStore(CommandContext context,RouteParty routeParty,String merchantId,String storeId,ListQuery query){return safe(()->{
        listQuery(query);if(routeParty==null)throw invalid();if(routeParty==RouteParty.USER)throw forbidden();queryActor(context,routeParty);id(merchantId);id(storeId);top();
        return tx.execute(s->{defaults();guard.acquire(List.of(storeId),system(context));
            String access=authorityVersion(authority.requireStoreRead(context,routeParty,merchantId,storeId));
            var filters=listFilters(query);filters.put("merchant",id(merchantId));filters.put("store",id(storeId));
            var result=casePage(query,filters);
            before(()->{if(!access.equals(authorityVersion(authority.requireStoreRead(context,routeParty,merchantId,storeId))))throw forbidden();});return result;
        });
    });}
    private static void listQuery(ListQuery q){
        if(q==null||q.page()<1||q.page()>10000||q.pageSize()<1||q.pageSize()>50)throw invalid();
        if(q.status()!=null&&!LIVE.contains(q.status())&&!ENDED.contains(q.status()))throw invalid();if(q.orderId()!=null)id(q.orderId());
    }
    private static Map<String,Object> listFilters(ListQuery q){return values("user",null,"merchant",null,"store",null,"status",q.status(),"order",optionalId(q.orderId()),"limit",q.pageSize(),"offset",(q.page()-1)*q.pageSize());}
    private CasePage casePage(ListQuery q,Map<String,Object> filters){
        long total=db.countCases(filters);var items=new ArrayList<CaseSummary>();
        for(var row:db.listCases(filters)){
            validCaseRow(row);origin(row);
            items.add(new CaseSummary(str(row.id),str(row.orderId),str(row.merchantId),str(row.storeId),row.status,str(row.version),row.sourceStage,row.typeCode,row.demandCode,row.requestedAmount,time(offset(row.createdAt)),time(offset(row.eligibilityDeadline))));
        }
        return new CasePage(q.page(),q.pageSize(),total,items);
    }
    private ReadAuthority selectedRead(CommandContext context,AfterSaleWorkflowMapper.CaseRow row,RouteParty routeParty){
        queryActor(context,routeParty);if(routeParty==RouteParty.USER&&!context.operatorId().equals(str(row.userId)))throw forbidden();
        var result=routeParty==null?authority.requireRead(context,resource(row)):authority.requireRead(context,resource(row),routeParty);
        if(result==null||result.party()==null||!Set.of("USER","MERCHANT","OPS").contains(result.party())||routeParty!=null&&!routeParty.name().equals(result.party()))throw bad();authorityVersion(result.authzVersion());return result;
    }
    private static String authorityVersion(String value){if(value==null||value.isBlank()||value.length()>256)throw bad();return value;}
    private static void queryActor(CommandContext context,RouteParty routeParty){
        if(context==null||context.operatorType()==null||context.traceId()==null||context.traceId().isBlank())throw invalid();id(context.operatorId());
        if(!Set.of(OperatorType.USER,OperatorType.PLATFORM_OPERATOR).contains(context.operatorType()))throw forbidden();
        if(routeParty==RouteParty.OPS&&context.operatorType()!=OperatorType.PLATFORM_OPERATOR||routeParty!=null&&routeParty!=RouteParty.OPS&&context.operatorType()!=OperatorType.USER)throw forbidden();
    }
    @Override public CaseFact requireCurrent(String orderId,String storeId,String expectedCurrentCaseId,QueryContext context,DataSource transactionSource){return owned(()->{
        scope(storeId,context,transactionSource);var active=db.active(id(orderId));if(active.size()>1)throw bad();
        if(expectedCurrentCaseId==null){if(!active.isEmpty())throw bad();return new CaseFact(null,orderId,null,null,storeId,null,null,false,null);}
        var row=db.row(id(expectedCurrentCaseId));if(row==null||!orderId.equals(str(row.orderId))||!storeId.equals(str(row.storeId))||row.version==null||row.version<0||row.activeFlag==null)throw bad();
        boolean isActive=row.activeFlag==1;if(isActive){if(!LIVE.contains(row.status)||active.size()!=1||!row.id.equals(active.get(0).id))throw bad();}
        else if(row.activeFlag!=0||!ENDED.contains(row.status)||!active.isEmpty())throw bad();
        if(!Set.of("VERIFIED","UNVERIFIED_POST_START").contains(row.sourceStage))throw bad();origin(caseRow(row.id));
        if("WAITING_SUPPLEMENT".equals(row.status))currentSupplement(row);else if(row.currentSupplementId!=null||!db.openSupplements(row.id).isEmpty())throw bad();
        return new CaseFact(str(row.id),orderId,str(row.userId),str(row.merchantId),storeId,row.status,row.sourceStage,isActive,str(row.version));
    });}

    @Override public DecisionFact requirePendingDecision(String caseId,String decisionId,String orderToken,String storeId,QueryContext context,DataSource transactionSource){return owned(()->{
        scope(storeId,context,transactionSource);var d=db.decision(id(decisionId));if(d==null||!caseId.equals(str(d.aftersaleId))||!storeId.equals(str(d.storeId))||d.refundOrderId!=null||orderToken==null||!sha(bytes(orderToken)).equals(d.orderTokenHash))throw bad();
        var current=liveCommands.getOrDefault(TransactionSynchronizationManager.getResource(source),Map.of());if(!orderToken.equals(current.get(d.commandId)))throw bad();
        var b=db.bindingById(d.commandId);if(b==null||!"RESERVED".equals(b.state))throw bad();var row=caseRow(id(caseId));if(!"PROCESSING".equals(row.status)||row.activeFlag!=1||!row.version.equals(d.sourceCaseVersion))throw bad();
        return decisionProof(d.id);
    });}
    @Override public CreatedFact requireCreated(String caseId,String decisionId,String refundOrderId,String storeId,QueryContext context,DataSource transactionSource){return owned(()->{
        scope(storeId,context,transactionSource);var d=db.decision(id(decisionId));if(d==null||!caseId.equals(str(d.aftersaleId))||!storeId.equals(str(d.storeId))||!refundOrderId.equals(nullable(d.refundOrderId))||d.refundNo==null||d.refundCreatedEventId==null||d.refundCreatedAt==null)throw bad();
        var proof=decisionProof(d.id);var row=caseRow(id(caseId));if(!"RESOLVED".equals(row.status)||row.activeFlag!=0||!d.id.equals(row.decisionId)||!d.refundOrderId.equals(row.refundOrderId)||row.version!=d.sourceCaseVersion+1)throw bad();
        if(d.refundCreatedAt.isBefore(d.decidedAt))throw bad();return new CreatedFact(proof,refundOrderId,str(d.refundNo),str(d.refundCreatedEventId),offset(d.refundCreatedAt));
    });}
    private DecisionFact decisionProof(long decisionId){
        var d=db.decision(decisionId);if(d==null)throw bad();var envelope=decode("DECISION_PROOF:"+decisionId,d.proofCipher,DecisionEnvelope.class);var p=envelope.decision();var row=caseRow(d.aftersaleId);var o=origin(row);if(!d.authzVersion.equals(envelope.authzVersion())||!d.scopeVersion.equals(envelope.scopeVersion())||!d.orderId.equals(row.orderId)||!d.storeId.equals(row.storeId)||!equal(d.paidAmount,p.payment().paidAmount()))throw bad();
        if(!str(d.id).equals(p.decisionId())||!str(row.id).equals(p.caseId())||!str(row.orderId).equals(p.orderId())||!str(row.storeId).equals(p.storeId())||!str(row.userId).equals(p.userId())||!str(row.merchantId).equals(p.merchantId())||!row.sourceStage.equals(p.sourceStage())||!str(d.sourceCaseVersion).equals(p.caseVersionBefore())||!str(d.commandId).equals(p.commandId())||!str(d.eventId).equals(p.decidedEventId())||!str(d.actorId).equals(p.operatorId())||!d.decisionType.equals(p.decisionType())||!equalNullable(d.refundAmount,p.refundAmount())||!offset(d.decidedAt).isEqual(p.decidedAt())||!o.fact().payment().equals(p.payment())||!o.fact().location().reservationId().equals(p.reservationId()))throw bad();
        String reason=decode("DECISION_REASON:"+d.id,d.reasonCipher,String.class);var b=db.bindingById(d.commandId);if(b==null||!"PLATFORM_OPERATOR".equals(string(b.actorType))||!d.actorId.equals(b.actorId)||!"aftersale.decide".equals(string(b.commandNamespace)))throw bad();
        commandProof(b,json(values("afterSaleId",p.caseId(),"version",p.caseVersionBefore(),"decisionType",p.decisionType(),"refundAmount",money(p.refundAmount()),"reason",reason)));
        if(NON_REFUND.contains(p.decisionType())){if(p.refundType()!=null||p.refundAmount()!=null||p.funding()!=null||d.orderTokenHash!=null)throw bad();}
        else{String type="FULL_REFUND".equals(p.decisionType())?"FULL":"PARTIAL_REFUND".equals(p.decisionType())?"PARTIAL":null;if(type==null||!type.equals(p.refundType()))throw bad();RefundFundingEvidenceChecks.requireAllowed(fundingCheck(row,o,p.decisionId(),p.commandId(),type,p.refundAmount()),p.funding(),p.decidedAt());}
        return p;
    }

    @Override public EvidenceAccess proveAccess(CommandContext context,String caseId,String batchId,String assetId,DataSource transactionSource){return evidenceAccess(context,null,caseId,batchId,assetId,transactionSource);}
    @Override public EvidenceAccess proveAccess(CommandContext context,RouteParty routeParty,String caseId,String batchId,String assetId,DataSource transactionSource){if(routeParty==null)throw invalid();return evidenceAccess(context,routeParty,caseId,batchId,assetId,transactionSource);}
    private EvidenceAccess evidenceAccess(CommandContext context,RouteParty routeParty,String caseId,String batchId,String assetId,DataSource transactionSource){return owned(()->{
        transaction(transactionSource);queryActor(context,routeParty);var hint=hint(id(caseId));guard.acquire(List.of(str(hint.storeId)),system(context));var row=caseRow(hint.id);var auth=selectedRead(context,row,routeParty);origin(row);
        var batch=db.batch(id(batchId));if(batch==null||!batch.aftersaleId.equals(row.id))throw forbidden();batchBody(batch);
        var asset=db.assets(batch.id).stream().filter(a->assetId.equals(str(a.assetId))).findFirst().orElseThrow(AfterSaleService::forbidden);
        var fact=asset(asset);assets.requireStillReady(fact,source);before(()->{if(!auth.equals(selectedRead(context,row,routeParty)))throw forbidden();});return new EvidenceAccess(caseId,batchId,assetId,str(asset.ownerUserId),asset.objectSha256,asset.objectVersionRef,asset.assetFactVersion,str(row.merchantId),str(row.storeId),str(row.version),auth.authzVersion());
    });}
    @Override public AfterSaleSupplementTimeoutApi.Result handle(AfterSaleSupplementTimeoutApi.Timeout c){return safe(()->{
        if(c==null)throw invalid();id(c.afterSaleId());id(c.supplementRequestId());id(c.storeId());var deadline=parseTime(c.expectedDeadline());
        if(c.context()==null||c.context().operatorType()!=OperatorType.SYSTEM||c.context().operatorId()!=null||!"ASYNC_TASK".equals(c.context().source())||!("TASK:AFTERSALE_SUPPLEMENT_TIMEOUT:"+c.supplementRequestId()+":0").equals(c.context().requestId()))throw forbidden();top();
        var preliminary=tx.execute(s->{defaults();guard.acquire(List.of(c.storeId()),system(c.context()));var row=caseRow(id(c.afterSaleId()));if(!c.storeId().equals(str(row.storeId)))throw bad();var round=round(row,id(c.supplementRequestId()));if(!deadline.isEqual(offset(round.deadline)))throw bad();tasks.requireTrusted(c.context(),taskSpec(row,round));origin(row);
            if(!LIVE.contains(row.status)||!"OPEN".equals(round.status)||!Objects.equals(row.currentSupplementId,round.id))return new AfterSaleSupplementTimeoutApi.Result(true,null);
            if(!"WAITING_SUPPLEMENT".equals(row.status))throw bad();var at=now();return at.isBefore(deadline)?new AfterSaleSupplementTimeoutApi.Result(false,time(deadline)):null;
        });if(preliminary!=null)return preliminary;
        var key=key("aftersale.supplement.timeout",c.context(),"AFTERSALE:"+c.afterSaleId());String purpose=purpose(key);byte[] input=json(values("afterSaleId",c.afterSaleId(),"supplementRequestId",c.supplementRequestId(),"storeId",c.storeId(),"deadline",time(deadline)));admit(key,purpose,input);
        return tx.execute(s->{defaults();var b=db.binding(key);same(b,purpose,input);guard.acquire(List.of(c.storeId()),system(c.context()));var row=caseRow(id(c.afterSaleId()));var round=round(row,id(c.supplementRequestId()));tasks.requireTrusted(c.context(),taskSpec(row,round));origin(row);
            if("SUCCEEDED".equals(b.state)){replaySystem(b,purpose);return new AfterSaleSupplementTimeoutApi.Result(true,null);}
            if(!LIVE.contains(row.status)||!"OPEN".equals(round.status)||!Objects.equals(row.currentSupplementId,round.id))return new AfterSaleSupplementTimeoutApi.Result(true,null);
            if(!"WAITING_SUPPLEMENT".equals(row.status)||!deadline.isEqual(offset(round.deadline)))throw bad();if(now().isBefore(deadline))return new AfterSaleSupplementTimeoutApi.Result(false,time(deadline));
            activate(b.id,"");requireOrderCurrent(row);var st=new State(row,b,c.context());closeRound(row,round,"TIMED_OUT",b.id,st.at);st.supplement=null;st.affectedRound=round.id;st.to="PROCESSING";st.action="SUPPLEMENT_TIMED_OUT";complete(st,purpose);return new AfterSaleSupplementTimeoutApi.Result(true,null);
        });
    });}
    /** Bounded source-driven recovery. One damaged proof is quarantined without starving following work. */
    public synchronized int reconcileTasks(){top();var scanned=db.scanDue(scanAfter);if(scanned.isEmpty()){scanAfter=0;return 0;}int count=0;
        for(var candidate:scanned){try{tx.executeWithoutResult(s->{defaults();var hint=hint(candidate.aftersaleId);var q=new QueryContext("aftersale-supplement-recovery",OperatorType.SYSTEM,null);guard.acquire(List.of(str(hint.storeId)),q);var row=caseRow(hint.id);var round=round(row,candidate.id);
                    if(LIVE.contains(row.status)&&"OPEN".equals(round.status)){if(!"WAITING_SUPPLEMENT".equals(row.status)||!round.id.equals(row.currentSupplementId))throw bad();origin(row);requireOrderCurrent(row);if(!now().isBefore(offset(round.deadline))){try{tasks.recover(taskSpec(row,round));}catch(IllegalArgumentException conflict){throw error("AFTERSALE_TASK_CONFLICT");}}}
                    db.resolveIssue(round.id);
                });}catch(ApiException failure){if(infrastructure(failure)||!Set.of(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"AFTERSALE_TASK_CONFLICT","AFTERSALE_NOT_FOUND").contains(failure.code()))throw failure;
                tx.executeWithoutResult(s->{defaults();var hint=db.hint(candidate.aftersaleId);db.recordIssue(candidate.id,candidate.aftersaleId,hint==null?null:hint.storeId,"AFTERSALE_TASK_CONFLICT".equals(failure.code())?"AFTERSALE_TASK_CONFLICT":"AFTERSALE_PROOF_INVALID");});}
            scanAfter=candidate.id;count++;
        }return count;
    }
    private TaskSpec taskSpec(AfterSaleWorkflowMapper.CaseRow row,AfterSaleWorkflowMapper.Supplement round){
        return new TaskSpec("AFTERSALE_SUPPLEMENT_TIMEOUT:"+round.id+":0","AFTERSALE_SUPPLEMENT_TIMEOUT","AFTERSALE_SUPPLEMENT",round.id,0L,new String(json(values("afterSaleId",str(row.id),"supplementRequestId",str(round.id),"storeId",str(row.storeId),"expectedDeadline",time(offset(round.deadline)))),StandardCharsets.UTF_8),8,"AFTERSALE_SUPPLEMENT",offset(round.deadline));
    }
    private AfterSaleWorkflowMapper.Supplement round(AfterSaleWorkflowMapper.CaseRow row,long id){var r=db.supplement(id);if(r==null||!row.id.equals(r.aftersaleId)||r.deadline==null||r.createdAt==null||!r.deadline.isAfter(r.createdAt)||!Set.of("USER","MERCHANT").contains(r.targetParty))throw bad();
        if(!Set.of("OPEN","SUBMITTED","TIMED_OUT","CANCELED").contains(r.status))throw bad();
        if("OPEN".equals(r.status)){if(r.closedAt!=null||r.completionCommandId!=null||r.completionVerificationId!=null)throw bad();}
        else {
            if(r.closedAt==null||(r.completionCommandId==null)==(r.completionVerificationId==null))throw bad();
            if(r.completionVerificationId!=null){var proof=db.verificationProof(row.id);if(!"CANCELED".equals(r.status)||!"INVALIDATED".equals(row.status)||proof==null||!Boolean.TRUE.equals(proof.invalidated)||!r.completionVerificationId.equals(proof.verificationId)||!row.id.equals(proof.aftersaleId)||!row.orderId.equals(proof.orderId)||!row.storeId.equals(proof.storeId)||!r.closedAt.equals(proof.verifiedAt))throw bad();}
        }
        String reason=decode("SUPPLEMENT:"+id,r.reasonCipher,String.class);var b=db.bindingById(r.requestCommandId);if(b==null||!"aftersale.supplement.request".equals(string(b.commandNamespace)))throw bad();
        var t=db.transitionByCommand(b.id);if(t==null||!row.id.equals(t.aftersaleId)||!r.id.equals(t.supplementId)||!"SUPPLEMENT_REQUESTED".equals(t.action))throw bad();
        commandProof(b,json(values("afterSaleId",str(row.id),"version",str(t.caseVersion-1),"target",r.targetParty,"reason",reason,"deadline",time(offset(r.deadline)))));return r;
    }
    private AfterSaleWorkflowMapper.Supplement currentSupplement(AfterSaleWorkflowMapper.CaseRow row){if(row.currentSupplementId==null)throw bad();var r=round(row,row.currentSupplementId);var open=db.openSupplements(row.id);if(!"OPEN".equals(r.status)||open.size()!=1||!open.get(0).id.equals(r.id))throw bad();return r;}
    private void closeRound(AfterSaleWorkflowMapper.CaseRow row,AfterSaleWorkflowMapper.Supplement r,String status,long command,OffsetDateTime at){one(db.closeSupplement(values("case",row.id,"supplement",r.id,"status",status,"command",command,"at",utc(at))));}

    public record Origin(OrderAfterSaleFactsApi.Fact fact,RefundApplicationHistoryFactsApi.Rejected rejection,List<String> priorFinalCases){public Origin{priorFinalCases=List.copyOf(priorFinalCases);}}
    public record DecisionEnvelope(DecisionFact decision,String authzVersion,String scopeVersion) {}
    public record Content(String description,String newProblemStatement) {}
    public record BatchBody(String text,String opinionCode,List<Asset> assets){public BatchBody{assets=List.copyOf(assets);}}
    private Origin origin(AfterSaleWorkflowMapper.CaseRow row){
        var latest=db.latestTransition(row.id);if("INVALIDATED".equals(row.status)){var verification=db.verificationProof(row.id);if(verification==null||!Boolean.TRUE.equals(verification.invalidated)||!row.orderId.equals(verification.orderId)||!row.storeId.equals(verification.storeId)||!row.version.equals(verification.caseVersion)||!"INVALIDATED".equals(verification.caseStatus)||!Objects.equals(row.invalidatedAt,verification.verifiedAt)||latest==null||latest.caseVersion+1!=row.version)throw bad();}else if(latest==null||!row.version.equals(latest.caseVersion)||!row.status.equals(latest.toStatus)||!row.orderId.equals(latest.orderId))throw bad();
        var p=decode("CASE_ORIGIN:"+row.id,row.originCipher,Origin.class);var loc=p.fact().location();
        if(!str(row.orderId).equals(loc.orderId())||!str(row.userId).equals(loc.userId())||!str(row.merchantId).equals(loc.merchantId())||!str(row.storeId).equals(loc.storeId())||row.eligibilityAnchor==null||row.eligibilityDeadline==null||!row.eligibilityDeadline.equals(row.eligibilityAnchor.plusDays(7))||!row.finalSetVersion.equals(finalVersion(p.priorFinalCases()))||!Objects.equals(row.cityCode,p.fact().cityCode())||!row.scopeVersion.equals(p.fact().scopeVersion()))throw bad();
        var anchor="VERIFIED".equals(row.sourceStage)?p.fact().verifiedAt():p.fact().appointmentStart();if(anchor==null||!offset(row.eligibilityAnchor).isEqual(anchor)||row.createdAt.isBefore(row.eligibilityAnchor)||row.createdAt.isAfter(row.eligibilityDeadline)||"UNVERIFIED_POST_START".equals(row.sourceStage)&&p.rejection()==null)throw bad();
        var creation=db.transitionByCommand(row.creatorCommandId);if(creation==null||!row.id.equals(creation.aftersaleId)||!row.orderId.equals(creation.orderId)||!row.createdEventId.equals(creation.eventId)||!"CREATED".equals(creation.action)||!"PENDING".equals(creation.toStatus)||!Long.valueOf(0).equals(creation.caseVersion)||!row.createdAt.equals(creation.occurredAt))throw bad();
        var content=decode("CASE_CONTENT:"+row.id,row.contentCipher,Content.class);var b=db.bindingById(row.creatorCommandId);if(b==null||!str(row.userId).equals(str(b.actorId))||!"USER".equals(string(b.actorType))||!"aftersale.create".equals(string(b.commandNamespace))||!("ORDER:"+row.orderId).equals(string(b.scope)))throw bad();
        var created=creation.evidenceBatchId==null?null:db.batch(creation.evidenceBatchId);
        if(created!=null){if(!row.creatorCommandId.equals(created.commandId)||!row.id.equals(created.aftersaleId)||!row.userId.equals(created.submitterId)||!"USER".equals(created.submitterType)||!row.createdAt.equals(created.createdAt))throw bad();var body=batchBody(created);commandProof(b,json(values("orderId",str(row.orderId),"typeCode",row.typeCode,"demandCode",row.demandCode,"description",content.description(),"requestedAmount",money(row.requestedAmount),"assets",sorted(body.assets().stream().map(Asset::assetId).toList()),"newProblemStatement",content.newProblemStatement())));}
        else if(!isLive(b.id))throw bad();
        return p;
    }
    private AfterSaleWorkflowMapper.CaseRow hint(long id){var row=db.hint(id);if(row==null)throw error("AFTERSALE_NOT_FOUND");return row;}
    private AfterSaleWorkflowMapper.CaseRow caseRow(long id){var row=db.row(id);validCaseRow(row);return row;}
    private static void validCaseRow(AfterSaleWorkflowMapper.CaseRow row){if(row==null)throw error("AFTERSALE_NOT_FOUND");if(!Objects.equals(row.workflowRevision,1)||row.creatorCommandId==null||row.createdEventId==null||row.scopeVersion==null||row.originCipher==null||row.contentCipher==null||row.version==null||row.version<0)throw bad();}
    private List<String> finalCases(long order){var result=new ArrayList<String>();for(var row:db.finals(order)){if(row.workflowRevision==null||row.decisionId==null||!Objects.equals(row.activeFlag,0))throw bad();var d=db.decision(row.decisionId);if(d==null||!row.id.equals(d.aftersaleId)||!NON_REFUND.contains(d.decisionType))throw bad();decisionProof(d.id);result.add(str(row.id));}return List.copyOf(result);}
    private static String finalVersion(List<String> finalCases){return sha(json(finalCases));}
    private Resource resource(AfterSaleWorkflowMapper.CaseRow row){return new Resource(str(row.id),str(row.orderId),str(row.userId),str(row.merchantId),str(row.storeId),row.cityCode,row.scopeVersion);}
    private void requireOrderCurrent(AfterSaleWorkflowMapper.CaseRow row){var c=orders.current(str(row.orderId),str(row.storeId),new QueryContext("aftersale-current",OperatorType.SYSTEM,null),source);if(c==null||!str(row.id).equals(c.caseId())||!row.status.equals(c.status()))throw bad();}
    private RefundFundingEligibilityFactsApi.FundingCheck fundingCheck(AfterSaleWorkflowMapper.CaseRow row,Origin origin,String decision,String command,String refundType,BigDecimal amount){var p=origin.fact().payment();return new RefundFundingEligibilityFactsApi.FundingCheck(str(row.orderId),p.paymentId(),p.paymentNo(),p.paymentSuccessEventId(),p.channelTradeNo(),str(row.userId),str(row.merchantId),str(row.storeId),str(row.id),decision,command,refundType,amount,p.paidAmount(),p.currency(),"DECISION_COMMIT",null,null,null);}

    private long insertBatch(AfterSaleWorkflowMapper.CaseRow row,long command,String party,CommandContext c,Long supplement,String text,String opinion,List<Asset> owned,Approval approval,OffsetDateTime at){
        long batch=next();var body=new BatchBody(text,opinion,owned);one(db.insertBatch(values("batch",batch,"case",row.id,"command",command,"party",party,"actor",id(c.operatorId()),"supplement",supplement,"content",encrypt("EVIDENCE_BATCH:"+batch,body),"hash",sha(json(body)),"policy",approval.policyVersion(),"at",utc(at))));
        for(var a:owned)one(db.insertAsset(values("batch",batch,"asset",id(a.assetId()),"owner",id(a.ownerUserId()),"hash",a.objectSha256(),"objectVersion",a.objectVersionRef(),"factVersion",a.factVersion(),"media",a.mediaType(),"bytes",a.bytes())));return batch;
    }
    private BatchBody batchBody(AfterSaleWorkflowMapper.Batch batch){
        var body=decode("EVIDENCE_BATCH:"+batch.id,batch.contentCipher,BatchBody.class);if(!sha(json(body)).equals(batch.contentSha256))throw bad();var persisted=db.assets(batch.id);if(persisted.size()!=body.assets().size())throw bad();
        var expected=body.assets().stream().sorted(Comparator.comparing(Asset::assetId)).toList();var actual=persisted.stream().map(AfterSaleService::asset).sorted(Comparator.comparing(Asset::assetId)).toList();if(!expected.equals(actual))throw bad();
        var b=db.bindingById(batch.commandId);var t=db.transitionByCommand(batch.commandId);
        if(b==null||t==null||!batch.id.equals(t.evidenceBatchId)||!batch.aftersaleId.equals(t.aftersaleId)||!batch.submitterId.equals(b.actorId)||!batch.submitterId.equals(t.actorId)||!"USER".equals(string(b.actorType))||!"USER".equals(t.actorType)||!batch.createdAt.equals(t.occurredAt)||!Set.of("USER","MERCHANT").contains(batch.submitterType))throw bad();
        String namespace=string(b.commandNamespace);
        if("aftersale.create".equals(namespace)){if(!"CREATED".equals(t.action)||!"USER".equals(batch.submitterType)||batch.supplementId!=null||body.opinionCode()!=null)throw bad();}
        else {
            boolean opinion="aftersale.opinion.submit".equals(namespace);
            if((!opinion&&!"aftersale.evidence.submit".equals(namespace))||t.caseVersion==null||t.caseVersion<=0||!(opinion?"MERCHANT_OPINION_ADDED":"EVIDENCE_ADDED").equals(t.action)||opinion&&!"MERCHANT".equals(batch.submitterType)||!opinion&&body.opinionCode()!=null||!("AFTERSALE:"+batch.aftersaleId).equals(string(b.scope)))throw bad();
            if(batch.supplementId!=null&&!batch.supplementId.equals(t.supplementId))throw bad();
            var expectedInput=values("afterSaleId",str(batch.aftersaleId),"version",str(t.caseVersion-1),"supplementRequestId",nullable(t.supplementId),"text",body.text(),"opinion",body.opinionCode(),"assets",sorted(body.assets().stream().map(Asset::assetId).toList()));
            var route=bindingRoute(b);if(route!=null){if(opinion||!route.name().equals(batch.submitterType))throw bad();expectedInput.put("routeParty",route.name());}commandProof(b,json(expectedInput));
        }
        return body;
    }
    private static Asset asset(AfterSaleWorkflowMapper.AssetRow a){return new Asset(str(a.assetId),str(a.ownerUserId),a.objectSha256,a.objectVersionRef,a.assetFactVersion,a.mediaType,a.assetBytes);}
    private List<Asset> resolveAssets(String owner,List<String> requested){if(requested.isEmpty())return List.of();var result=assets.requireReadyOwned(owner,sorted(requested),source);if(result==null||result.size()!=requested.size())throw bad();var seen=new HashSet<String>();for(var a:result){if(a==null||!owner.equals(a.ownerUserId())||!requested.contains(a.assetId())||!seen.add(a.assetId())||a.objectSha256()==null||!a.objectSha256().matches("[0-9a-f]{64}")||a.objectVersionRef()==null||a.factVersion()==null||a.mediaType()==null||!a.mediaType().startsWith("image/")||a.bytes()<=0)throw bad();assets.requireStillReady(a,source);}return result.stream().sorted(Comparator.comparing(Asset::assetId)).toList();}
    private Approval moderate(String text){if(text==null)return new Approval(sha(bytes("")),"NO_TEXT",true);var result=moderation.check(text);if(result==null||result.policyVersion()==null||result.policyVersion().isBlank()||result.policyVersion().length()>128||!sha(bytes(text)).equals(result.textSha256()))throw bad();if(!result.allowed())throw error("AFTERSALE_CONTENT_REJECTED");return result;}

    /** Completed commands reauthorize without rerunning mutable catalog/moderation providers. */
    private Receipt committed(Map<String,Object> key,String purpose,byte[] input,CommandContext context){return tx.execute(s->{
        defaults();var b=db.binding(key);if(b==null)return null;same(b,purpose,input);if(!"SUCCEEDED".equals(b.state))return null;
        var receipt=replaySystem(b,purpose);var hint=hint(id(receipt.afterSaleId()));guard.acquire(List.of(str(hint.storeId)),system(context));return replay(b,purpose,context);
    });}
    private void admit(Map<String,Object> key,String purpose,byte[] input){tx.executeWithoutResult(s->{defaults();var v=new LinkedHashMap<>(key);v.put("id",next());v.put("hash",sha(input));v.put("canonical",protection.protect(purpose,input));db.reserve(v);same(db.binding(key),purpose,input);});}
    private void same(AfterSaleWorkflowMapper.Binding b,String purpose,byte[] input){if(b==null||!"canonical-v1".equals(b.canonicalVersion))throw bad();if(!sha(input).equals(b.payloadSha256)||!MessageDigest.isEqual(input,protection.reveal(purpose,b.canonicalBytes)))throw error(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT);}
    private Receipt replay(AfterSaleWorkflowMapper.Binding b,String purpose,CommandContext context){
        var receipt=replaySystem(b,purpose);var row=caseRow(id(receipt.afterSaleId()));authority.requireRead(context,resource(row));
        var route=bindingRoute(b);if(route!=null){var access=selectedRead(context,row,route);before(()->{if(!access.equals(selectedRead(context,row,route)))throw forbidden();authority.requireUser(context);if(route==RouteParty.MERCHANT)authority.requireOwner(context,resource(row));});}
        switch(string(b.commandNamespace)){
            case "aftersale.accept","aftersale.supplement.request","aftersale.duplicate.close" -> authority.requireAdmin(context,resource(row),"aftersale.handle");
            case "aftersale.decide" -> authority.requireAdmin(context,resource(row),"aftersale.decide");
            case "aftersale.create","aftersale.withdraw" -> {authority.requireUser(context);if(!str(row.userId).equals(context.operatorId()))throw forbidden();}
            case "aftersale.opinion.submit" -> {authority.requireUser(context);authority.requireOwner(context,resource(row));}
            case "aftersale.evidence.submit" -> {authority.requireUser(context);var batch=db.batch(id(receipt.evidenceBatchId()));if(batch==null||!row.id.equals(batch.aftersaleId)||!b.id.equals(batch.commandId)||!context.operatorId().equals(str(batch.submitterId)))throw bad();batchBody(batch);if("MERCHANT".equals(batch.submitterType))authority.requireOwner(context,resource(row));else if(!"USER".equals(batch.submitterType)||!str(row.userId).equals(context.operatorId()))throw forbidden();}
            default -> throw bad();
        }
        return receipt;
    }
    private RouteParty bindingRoute(AfterSaleWorkflowMapper.Binding b){
        String purpose=purpose(values("namespace",b.commandNamespace,"actorType",b.actorType,"actor",b.actorId,"scope",b.scope,"requestId",b.requestId));
        var input=decode(purpose,b.canonicalBytes,Map.class);if(!input.containsKey("routeParty"))return null;
        Object route=input.get("routeParty");if(!"aftersale.evidence.submit".equals(string(b.commandNamespace))||!(route instanceof String value)||!Set.of("USER","MERCHANT").contains(value))throw bad();
        return RouteParty.valueOf(value);
    }
    private Receipt replaySystem(AfterSaleWorkflowMapper.Binding b,String purpose){if(!Objects.equals(b.resultVersion,1)||b.resultBytes==null)throw bad();var saved=decode(purpose+":RESULT",b.resultBytes,Receipt.class);var t=db.transitionByCommand(b.id);if(t==null)throw bad();var expected=new Receipt(str(t.commandId),str(t.orderId),str(t.aftersaleId),t.toStatus,str(t.caseVersion),time(offset(t.occurredAt)),nullable(t.evidenceBatchId),nullable(t.supplementId),nullable(t.decisionId),nullable(t.refundOrderId));if(!saved.equals(expected))throw bad();return saved;}
    private void finish(AfterSaleWorkflowMapper.Binding b,String purpose,Receipt receipt){one(db.succeed(b.id,encrypt(purpose+":RESULT",receipt)));}
    private void activate(long command,String token){var resource=TransactionSynchronizationManager.getResource(source);if(resource==null)throw bad();liveCommands.computeIfAbsent(resource,k->new ConcurrentHashMap<>()).put(command,token);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){liveCommands.remove(resource);}});}
    private boolean isLive(long command){return liveCommands.getOrDefault(TransactionSynchronizationManager.getResource(source),Map.of()).containsKey(command);}
    private void commandProof(AfterSaleWorkflowMapper.Binding b,byte[] expected){if(!"SUCCEEDED".equals(b.state)&&(!"RESERVED".equals(b.state)||!isLive(b.id)))throw bad();try{same(b,purpose(values("namespace",b.commandNamespace,"actorType",b.actorType,"actor",b.actorId,"scope",b.scope,"requestId",b.requestId)),expected);}catch(ApiException failure){throw bad();}}
    private void scope(String store,QueryContext context,DataSource txSource){transaction(txSource);id(store);if(context==null||context.operatorType()!=OperatorType.SYSTEM||context.operatorId()!=null)throw forbidden();guard.requireHeld(store,source);}
    private void transaction(DataSource txSource){if(source!=txSource||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();}
    private <T>T owned(Supplier<T> work){try{return work.get();}catch(RuntimeException e){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();throw e instanceof ApiException a?a:bad(e);}}
    private static <T>T safe(Supplier<T> work){try{return work.get();}catch(ApiException e){throw e;}catch(RuntimeException e){throw bad(e);}}
    private static boolean infrastructure(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof org.springframework.dao.DataAccessException||t instanceof org.springframework.transaction.TransactionException||t instanceof java.sql.SQLException)return true;return false;}
    private static void top(){if(TransactionSynchronizationManager.isActualTransactionActive())throw bad();}
    private void defaults(){db.utc();db.lockWait();}private OffsetDateTime now(){return offset(db.now());}
    private long next(){long id=ids.nextId();if(id<=0)throw bad();return id;}
    private static void before(Runnable action){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){if(readOnly)throw bad();action.run();}});}
    private void publish(long event,String type,String caseId,OffsetDateTime at,CommandContext c,Map<String,Object> payload){events.publish(new IntegrationEvent<>(str(event),type,1,at,"AFTERSALE",caseId,c.traceId(),payload));}
    private byte[] encrypt(String purpose,Object value){return protection.protect(purpose,json(value));}
    private <T>T decode(String purpose,byte[] value,Class<T> type){try{return JSON.readValue(protection.reveal(purpose,value),type);}catch(Exception e){throw bad();}}
    private static void command(CommandContext c){if(c==null||c.operatorType()==null||!Set.of(OperatorType.USER,OperatorType.PLATFORM_OPERATOR).contains(c.operatorType())||c.traceId()==null||c.traceId().isBlank())throw invalid();id(c.operatorId());try{PublicContractChecks.requireRequestId(c.requestId());}catch(IllegalArgumentException failure){throw invalid();}}
    private static void user(CommandContext c){command(c);if(c.operatorType()!=OperatorType.USER)throw forbidden();}
    private static void assetIds(List<String> ids){if(ids==null||ids.size()>6||new HashSet<>(ids).size()!=ids.size())throw invalid();ids.forEach(AfterSaleService::id);}
    private static List<String> sorted(List<String> ids){return ids.stream().sorted().toList();}
    private static void optionalAmount(BigDecimal amount){if(amount!=null&&(amount.signum()<0||amount.scale()>2||amount.precision()-amount.scale()>16))throw error("AFTERSALE_AMOUNT_INVALID");}
    private static String money(BigDecimal amount){return amount==null?null:amount.setScale(2).toPlainString();}
    private static void text(String s,int min,int max){if(s==null||s.codePointCount(0,s.length())<min||s.codePointCount(0,s.length())>max||s.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw invalid();}
    private static long version(String v){if(v==null||!v.matches("0|[1-9][0-9]{0,18}"))throw invalid();try{return Long.parseLong(v);}catch(Exception e){throw invalid();}}
    private static long id(String value){try{return IDS.fromApi(value);}catch(IllegalArgumentException failure){throw invalid();}}private static Long optionalId(String value){return value==null?null:id(value);}
    private static String str(Long id){if(id==null||id<0)throw bad();return id.toString();}private static String str(long id){if(id<0)throw bad();return Long.toString(id);}private static String nullable(Long id){return id==null?null:str(id);}
    private static OffsetDateTime parseTime(String value){try{var t=OffsetDateTime.parse(value);PublicContractChecks.requireMillisecondPrecision(t);return t.withOffsetSameInstant(ZoneOffset.UTC);}catch(Exception e){throw invalid();}}
    private static OffsetDateTime offset(LocalDateTime value){if(value==null)throw bad();return value.atOffset(ZoneOffset.UTC);}private static LocalDateTime utc(OffsetDateTime value){if(value==null)throw bad();return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}private static String time(OffsetDateTime value){return value.withOffsetSameInstant(ZoneOffset.UTC).toString();}
    private static boolean equal(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}private static boolean equalNullable(BigDecimal a,BigDecimal b){return a==null?b==null:equal(a,b);}
    private static Map<String,Object> values(Object... values){var m=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    private static Map<String,Object> key(String ns,CommandContext c,String scope){return values("namespace",bytes(ns),"actorType",bytes(c.operatorType().name()),"actor",c.operatorId()==null?0L:id(c.operatorId()),"scope",bytes(scope),"requestId",bytes(c.requestId()));}
    private static String purpose(Map<String,Object> key){return "AFTERSALE_COMMAND:"+string((byte[])key.get("namespace"))+":"+string((byte[])key.get("actorType"))+":"+key.get("actor")+":"+string((byte[])key.get("scope"))+":"+string((byte[])key.get("requestId"));}
    private static QueryContext system(CommandContext c){return new QueryContext(c==null?"aftersale":c.traceId(),OperatorType.SYSTEM,null);}
    private static byte[] json(Object value){try{return JSON.writeValueAsBytes(value);}catch(Exception e){throw bad();}}private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}private static String string(byte[] value){return value==null?null:new String(value,StandardCharsets.UTF_8);}
    public static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw bad();}}
    private static void one(int count){if(count!=1)throw bad();}private static void reserved(AfterSaleWorkflowMapper.Binding b){if(!"RESERVED".equals(b.state))throw bad();}
    private static ApiException invalid(){return error(CommonApiCodes.INVALID_ARGUMENT);}private static ApiException forbidden(){return error(CommonApiCodes.FORBIDDEN);}private static ApiException state(){return error("AFTERSALE_STATE_NOT_ALLOWED");}private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}private static ApiException bad(Throwable cause){var error=bad();error.initCause(cause);return error;}
    private static ApiException error(String code){return new ApiException(code,"Aftersale action unavailable; retain the original request ID");}
}
