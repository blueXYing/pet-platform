package com.petplatform.boot.adapter.web.aftersale;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi.Receipt;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.boot.config.*;
import com.petplatform.common.*;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import tools.jackson.databind.ObjectReader;

/** Contract51 boundary: route identity, bounded strict input, decimal strings and safe errors. */
final class AfterSaleHttpSupport {
    private static final ObjectReader JSON=MerchantJsonReaderFactory.strictReader();
    private AfterSaleHttpSupport() {}
    static RouteParty party(HttpServletRequest r) {
        if(r.getRequestURI().startsWith("/api/v1/c/"))return RouteParty.USER;
        if(r.getRequestURI().startsWith("/api/v1/merchant/"))return RouteParty.MERCHANT;
        if(r.getRequestURI().startsWith("/api/v1/admin/"))return RouteParty.OPS;
        throw new ApiException(CommonApiCodes.FORBIDDEN,"Forbidden");
    }
    static CommandContext context(HttpServletRequest r,boolean write,String action) {
        if(Collections.list(r.getHeaders("Authorization")).size()!=1)throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Session required");
        String requestId=null;
        if(write) {
            if(Collections.list(r.getHeaders("X-Request-Id")).size()!=1)throw invalid();
            try { requestId=PublicContractChecks.requireTerminalRequestId(r.getHeader("X-Request-Id")); }
            catch(IllegalArgumentException bad){throw invalid();}
        }
        if(party(r)==RouteParty.OPS) {
            if(!(r.getAttribute(AdminBearerAuthenticationFilter.VIEW) instanceof AdminSessionView s))throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Session required");
            if(!"ADMIN_WEB".equals(s.principal().audience())||!s.permissions().actionCodes().contains(action))throw new ApiException(CommonApiCodes.FORBIDDEN,"Action unavailable");
            return new CommandContext(requestId,trace(r),OperatorType.PLATFORM_OPERATOR,s.principal().operatorId(),"ADMIN_WEB");
        }
        if(!(r.getAttribute(CBearerSessionFilter.VIEW) instanceof MiniSessionView s))throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Session required");
        if(write&&!"ACTIVE".equals(s.userStatus()))throw new ApiException(CommonApiCodes.FORBIDDEN,"Action unavailable");
        return new CommandContext(requestId,trace(r),OperatorType.USER,s.userId(),"MINIAPP");
    }
    static <T>T body(HttpServletRequest r,Class<T> type) {
        onlyParameters(r);
        try {
            var content=MediaType.parseMediaType(r.getContentType());
            if(!"application".equalsIgnoreCase(content.getType())||!"json".equalsIgnoreCase(content.getSubtype()))throw invalid();
            byte[] raw=r.getInputStream().readNBytes(32769);
            if(raw.length==0||raw.length>32768)throw invalid();
            // Jackson's scalar-coercion switch does not reject every number-to-String path.
            // Contract51 command fields are strings/null, except the string-only asset array.
            var root=JSON.readTree(raw);if(root==null||!root.isObject())throw invalid();
            for(var field:root.properties()) {
                var value=field.getValue();
                if("evidenceAssetIds".equals(field.getKey())) {
                    if(!value.isArray())throw invalid();
                    for(var item:value)if(!item.isTextual())throw invalid();
                } else if(!value.isNull()&&!value.isTextual())throw invalid();
            }
            T value=JSON.forType(type).readValue(raw);if(value==null)throw invalid();return value;
        }catch(ApiException known){throw known;}catch(Exception malformed){throw invalid();}
    }
    static void onlyParameters(HttpServletRequest r,String... names) {
        var allowed=Set.of(names);
        if(!allowed.containsAll(r.getParameterMap().keySet()))throw invalid();
        for(var name:names)if(r.getParameterValues(name)!=null&&r.getParameterValues(name).length!=1)throw invalid();
    }
    static String id(String value) {
        try { if(value==null||!value.matches("[1-9][0-9]{0,18}")||Long.parseLong(value)<=0)throw invalid();return value; }
        catch(NumberFormatException bad){throw invalid();}
    }
    static String optionalId(String value){return value==null?null:id(value);}
    static String version(String value) {
        try {if(value==null||!value.matches("0|[1-9][0-9]{0,18}")||Long.parseLong(value)<0)throw invalid();return value;}
        catch(NumberFormatException bad){throw invalid();}
    }
    static BigDecimal amount(String value) {
        if(value==null)return null;
        if(!value.matches("(0|[1-9][0-9]{0,15})\\.[0-9]{2}"))throw invalid();
        return new BigDecimal(value);
    }
    static String deadline(String value) {
        try {
            if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))throw invalid();
            var parsed=OffsetDateTime.parse(value);PublicContractChecks.requireMillisecondPrecision(parsed);return value;
        }catch(RuntimeException bad){throw invalid();}
    }
    static String hash(String value){if(value!=null&&!value.matches("[0-9a-f]{64}"))throw invalid();return value;}
    static List<String> assets(List<String> value) {
        if(value==null||value.size()>6||new HashSet<>(value).size()!=value.size())throw invalid();
        value.forEach(AfterSaleHttpSupport::id);return List.copyOf(value);
    }
    static int number(String value,int fallback,int max) {
        if(value==null)return fallback;
        try { if(!value.matches("[1-9][0-9]{0,4}"))throw invalid();int n=Integer.parseInt(value);if(n>max)throw invalid();return n; }
        catch(NumberFormatException bad){throw invalid();}
    }
    static String text(String value,int min,int max,boolean optional) {
        if(value==null&&optional)return null;
        if(value==null||value.isBlank()||value.codePointCount(0,value.length())<min||value.codePointCount(0,value.length())>max
                ||value.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw invalid();return value;
    }
    static String oneOf(String value,String... values){if(value==null||!Set.of(values).contains(value))throw invalid();return value;}
    static String time(String value){return value==null?null:new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(OffsetDateTime.parse(value).toInstant());}
    static String money(BigDecimal value){return value==null?null:value.setScale(2).toPlainString();}
    static Map<String,Object> receipt(Receipt a){return map("commandId",a.commandId(),"orderId",a.orderId(),"afterSaleId",a.afterSaleId(),"status",a.status(),"version",a.version(),"occurredAt",time(a.occurredAt()),"evidenceBatchId",a.evidenceBatchId(),"supplementRequestId",a.supplementRequestId(),"decisionId",a.decisionId(),"refundOrderId",a.refundOrderId());}
    static Map<String,Object> eligibility(Eligibility a){return map("eligible",a.eligible(),"sourceStage",a.sourceStage(),"deadline",time(a.deadline()),"blockingReason",a.blockingReason(),"activeAfterSaleId",a.activeAfterSaleId());}
    static Map<String,Object> detail(CaseView a){return map("afterSaleId",a.afterSaleId(),"orderId",a.orderId(),"status",a.status(),"version",a.version(),"sourceStage",a.sourceStage(),"typeCode",a.typeCode(),"demandCode",a.demandCode(),"description",a.description(),"requestedAmount",money(a.requestedAmount()),"createdAt",time(a.createdAt()),"deadline",time(a.deadline()),"supplementRequestId",a.supplementRequestId(),"supplementTarget",a.supplementTarget(),"supplementDeadline",time(a.supplementDeadline()),"supplementReason",a.supplementReason(),"finalSetVersion",a.finalSetVersion(),"priorFinalCaseIds",a.priorFinalCaseIds(),"newProblemStatement",a.newProblemStatement(),"decisionType",a.decisionType(),"refundAmount",money(a.refundAmount()),"decisionReason",a.decisionReason(),"evidence",a.evidence().stream().map(b->map("batchId",b.batchId(),"submitterType",b.submitterType(),"text",b.text(),"opinionCode",b.opinionCode(),"submittedAt",time(b.submittedAt()),"assetIds",b.assetIds())).toList());}
    static Map<String,Object> page(CasePage p){return map("page",p.page(),"pageSize",p.pageSize(),"total",p.total(),"items",p.items().stream().map(a->map("afterSaleId",a.afterSaleId(),"orderId",a.orderId(),"merchantId",a.merchantId(),"storeId",a.storeId(),"status",a.status(),"version",a.version(),"sourceStage",a.sourceStage(),"typeCode",a.typeCode(),"demandCode",a.demandCode(),"requestedAmount",money(a.requestedAmount()),"createdAt",time(a.createdAt()),"deadline",time(a.deadline()))).toList());}
    static ResponseEntity<Map<String,Object>> ok(Object data,int status,HttpServletRequest r){return response(map("code","SUCCESS","message","ok","data",data,"traceId",trace(r)),status);}
    static ResponseEntity<Map<String,Object>> error(RuntimeException e,HttpServletRequest r) {
        String code=e instanceof ApiException a?a.code():e instanceof IllegalArgumentException?CommonApiCodes.INVALID_ARGUMENT:CommonApiCodes.INTERNAL_ERROR;
        int status=switch(code) {
            case "COMMON_INVALID_ARGUMENT","AFTERSALE_AMOUNT_INVALID"->400;
            case "COMMON_UNAUTHORIZED"->401;
            case "COMMON_FORBIDDEN"->403;
            case "COMMON_NOT_FOUND","AFTERSALE_NOT_FOUND","ORDER_NOT_FOUND"->404;
            case "COMMON_CONFLICT","IDEMPOTENCY_KEY_CONFLICT","AFTERSALE_NOT_ELIGIBLE","AFTERSALE_ALREADY_ACTIVE","AFTERSALE_REFUND_APPLICATION_ACTIVE",
                 "AFTERSALE_STATE_NOT_ALLOWED","AFTERSALE_ALREADY_INVALIDATED","AFTERSALE_DECISION_FINAL","AFTERSALE_REFUND_BLOCKED_BY_VERIFICATION",
                 "AFTERSALE_SUPPLEMENT_EXPIRED","AFTERSALE_SUPPLEMENT_STALE","REFUND_ORDER_ALREADY_EXISTS"->409;
            case "AFTERSALE_CONTENT_REJECTED"->422;
            case "COMMON_RATE_LIMITED"->429;
            case "COMMON_DEPENDENCY_UNAVAILABLE","AFTERSALE_PROOF_INVALID","AFTERSALE_TASK_CONFLICT"->503;
            default->500;
        };
        if(status==500)code=CommonApiCodes.INTERNAL_ERROR;
        String message=switch(status){case 400->"请求参数不合法";case 401->"登录已失效，请重新登录";case 403->"无权执行该操作";case 404->"售后资源不存在";case 409->"当前状态或请求参数冲突，请刷新后核对";case 422->"提交内容未通过审核";case 429->"请求过于频繁";default->"服务暂不可用，请稍后重试";};
        return response(map("code",code,"message",message,"data",null,"traceId",trace(r)),status);
    }
    private static ResponseEntity<Map<String,Object>> response(Map<String,Object> data,int status){return ResponseEntity.status(status).header("Cache-Control","no-store, private").header("Pragma","no-cache").header("X-Content-Type-Options","nosniff").body(data);}
    static String trace(HttpServletRequest r){String s=MDC.get(TraceContextFilter.TRACE_MDC_KEY);if(s!=null&&!s.isBlank())return s;Object existing=r.getAttribute("aftersaleTraceId");if(existing instanceof String t)return t;s=UUID.randomUUID().toString();r.setAttribute("aftersaleTraceId",s);return s;}
    static Map<String,Object> map(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static ApiException invalid(){return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid aftersale input");}
}
