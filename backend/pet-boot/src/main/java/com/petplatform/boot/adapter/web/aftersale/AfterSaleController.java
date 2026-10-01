package com.petplatform.boot.adapter.web.aftersale;

import static com.petplatform.boot.adapter.web.aftersale.AfterSaleHttpSupport.*;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi.*;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Real C / OWNER / OPS routes. Public funding decisions remain closed in this slice. */
@RestController
@ConditionalOnProperty(name="pet.aftersale.http.enabled",havingValue="true")
public final class AfterSaleController {
    private final AfterSaleCommandApi commands;
    private final AfterSaleQueryApi queries;
    public AfterSaleController(AfterSaleCommandApi commands,AfterSaleQueryApi queries){this.commands=Objects.requireNonNull(commands);this.queries=Objects.requireNonNull(queries);}
    public record CreateBody(String typeCode,String demandCode,String description,String requestedAmount,List<String> evidenceAssetIds,String newProblemStatement) {}
    public record EvidenceBody(String expectedVersion,String supplementRequestId,String text,List<String> evidenceAssetIds) {}
    public record OpinionBody(String expectedVersion,String supplementRequestId,String opinionCode,String explanation,List<String> evidenceAssetIds) {}
    public record VersionBody(String expectedVersion) {}
    public record AcceptBody(String expectedVersion,String newProblemAssessment,String expectedFinalSetVersion) {}
    public record SupplementBody(String expectedVersion,String targetParty,String reason,String deadline) {}
    public record CloseBody(String expectedVersion,String priorFinalCaseId,String reason) {}
    public record DecisionBody(String expectedVersion,String decisionType,String refundAmount,String reason) {}

    @GetMapping("/api/v1/c/aftersale-options")
    public ResponseEntity<Map<String,Object>> options(HttpServletRequest r){
        var c=context(r,false,null);if(r.getQueryString()!=null)throw invalid();onlyParameters(r);noBody(r);var a=queries.options(c);
        return ok(map("typeOptions",a.typeOptions().stream().map(o->map("code",o.code(),"label",o.label())).toList(),
                "demandOptions",a.demandOptions().stream().map(o->map("code",o.code(),"label",o.label())).toList()),200,r);
    }

    @PostMapping("/api/v1/c/orders/{orderId}/aftersales")
    public ResponseEntity<Map<String,Object>> create(@PathVariable String orderId,HttpServletRequest r) {
        var c=context(r,true,null);var b=body(r,CreateBody.class);
        var result=commands.createWithOutcome(new Create(c,id(orderId),text(b.typeCode(),1,64,false),text(b.demandCode(),1,64,false),
                text(b.description(),10,500,false),amount(b.requestedAmount()),assets(b.evidenceAssetIds()),text(b.newProblemStatement(),10,500,true)));
        return ok(receipt(result.receipt()),result.created()?201:200,r);
    }
    @GetMapping("/api/v1/c/orders/{orderId}/aftersale-eligibility")
    public ResponseEntity<Map<String,Object>> eligibility(@PathVariable String orderId,HttpServletRequest r){onlyParameters(r);return ok(AfterSaleHttpSupport.eligibility(queries.checkEligibility(context(r,false,null),id(orderId))),200,r);}

    @GetMapping({"/api/v1/c/aftersales","/api/v1/merchant/aftersales","/api/v1/admin/aftersales"})
    public ResponseEntity<Map<String,Object>> list(HttpServletRequest r) {
        var p=party(r);var c=context(r,false,"aftersale.read");
        if(p==RouteParty.USER)onlyParameters(r,"page","pageSize","status","orderId");
        else onlyParameters(r,"page","pageSize","status","orderId","merchantId","storeId");
        var q=new ListQuery(number(r.getParameter("page"),1,10000),number(r.getParameter("pageSize"),20,50),r.getParameter("status"),optionalId(r.getParameter("orderId")));
        var result=p==RouteParty.USER?queries.listMine(c,q):queries.listForStore(c,p,id(r.getParameter("merchantId")),id(r.getParameter("storeId")),q);
        return ok(page(result),200,r);
    }
    @GetMapping({"/api/v1/c/aftersales/{caseId}","/api/v1/merchant/aftersales/{caseId}","/api/v1/admin/aftersales/{caseId}"})
    public ResponseEntity<Map<String,Object>> get(@PathVariable String caseId,HttpServletRequest r){onlyParameters(r);return ok(detail(queries.getCase(context(r,false,"aftersale.read"),id(caseId),party(r))),200,r);}

    @PostMapping({"/api/v1/c/aftersales/{caseId}/evidence","/api/v1/merchant/aftersales/{caseId}/evidence"})
    public ResponseEntity<Map<String,Object>> evidence(@PathVariable String caseId,HttpServletRequest r) {
        var c=context(r,true,null);var b=body(r,EvidenceBody.class);
        return ok(receipt(commands.submitEvidence(new SubmitEvidence(c,id(caseId),version(b.expectedVersion()),optionalId(b.supplementRequestId()),text(b.text(),10,500,true),assets(b.evidenceAssetIds())),party(r))),200,r);
    }
    @PostMapping("/api/v1/merchant/aftersales/{caseId}/opinion")
    public ResponseEntity<Map<String,Object>> opinion(@PathVariable String caseId,HttpServletRequest r) {
        var c=context(r,true,null);var b=body(r,OpinionBody.class);
        return ok(receipt(commands.submitMerchantOpinion(new SubmitMerchantOpinion(c,id(caseId),version(b.expectedVersion()),optionalId(b.supplementRequestId()),
                oneOf(b.opinionCode(),"AGREE","PARTLY_AGREE","DISAGREE","NEED_USER_SUPPLEMENT"),text(b.explanation(),10,500,false),assets(b.evidenceAssetIds())))),200,r);
    }
    @PostMapping("/api/v1/c/aftersales/{caseId}/withdraw")
    public ResponseEntity<Map<String,Object>> withdraw(@PathVariable String caseId,HttpServletRequest r){var c=context(r,true,null);var b=body(r,VersionBody.class);return ok(receipt(commands.withdraw(new Withdraw(c,id(caseId),version(b.expectedVersion())))),200,r);}
    @PostMapping("/api/v1/admin/aftersales/{caseId}/accept")
    public ResponseEntity<Map<String,Object>> accept(@PathVariable String caseId,HttpServletRequest r){var c=context(r,true,"aftersale.handle");var b=body(r,AcceptBody.class);return ok(receipt(commands.accept(new Accept(c,id(caseId),version(b.expectedVersion()),text(b.newProblemAssessment(),1,500,true),hash(b.expectedFinalSetVersion())))),200,r);}
    @PostMapping("/api/v1/admin/aftersales/{caseId}/supplement-requests")
    public ResponseEntity<Map<String,Object>> supplement(@PathVariable String caseId,HttpServletRequest r){var c=context(r,true,"aftersale.handle");var b=body(r,SupplementBody.class);return ok(receipt(commands.requestSupplement(new RequestSupplement(c,id(caseId),version(b.expectedVersion()),oneOf(b.targetParty(),"USER","MERCHANT"),text(b.reason(),1,500,false),deadline(b.deadline())))),200,r);}
    @PostMapping("/api/v1/admin/aftersales/{caseId}/close-duplicate")
    public ResponseEntity<Map<String,Object>> close(@PathVariable String caseId,HttpServletRequest r){var c=context(r,true,"aftersale.handle");var b=body(r,CloseBody.class);return ok(receipt(commands.closeDuplicate(new CloseDuplicate(c,id(caseId),version(b.expectedVersion()),id(b.priorFinalCaseId()),text(b.reason(),1,500,false)))),200,r);}
    @PostMapping("/api/v1/admin/aftersales/{caseId}/decisions")
    public ResponseEntity<Map<String,Object>> decide(@PathVariable String caseId,HttpServletRequest r) {
        var c=context(r,true,"aftersale.decide");var b=body(r,DecisionBody.class);id(caseId);version(b.expectedVersion());text(b.reason(),1,500,false);
        String type=oneOf(b.decisionType(),"REJECT","RESERVICE","OTHER","FULL_REFUND","PARTIAL_REFUND");var refund=amount(b.refundAmount());
        if(Set.of("FULL_REFUND","PARTIAL_REFUND").contains(type)) {
            if(refund==null||refund.signum()<=0)throw invalid();
            queries.getCase(c,caseId,RouteParty.OPS);
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Public funding decisions unavailable");
        }
        if(refund!=null)throw invalid();
        return ok(receipt(commands.decide(new Decide(c,caseId,b.expectedVersion(),type,null,b.reason()))),200,r);
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> failure(RuntimeException failure,HttpServletRequest r){return error(failure,r);}
}
