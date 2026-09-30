package com.petplatform.boot.adapter.web.aftersale;

import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.admin.biz.application.AdminAuthFailure;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.AfterSalePrivateAssetApi.EvidencePrincipal;
import com.petplatform.user.biz.application.UserAuthService;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.util.*;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectReader;

/** HTTP-only identity and parsing. No caller-supplied owner, party or session can authorize evidence. */
final class EvidenceHttpSupport {
    static final long MAX_UPLOAD_BYTES=10L*1024*1024;
    static final long MAX_RENDERED_BYTES=20L*1024*1024;
    static final Set<String> IMAGES=Set.of("image/jpeg","image/png");
    private static final ObjectReader JSON=MerchantJsonReaderFactory.strictReader();
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private static final java.time.format.DateTimeFormatter TIME=new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final UserAuthService users;
    private final AdminSessionQueryApi admins;

    EvidenceHttpSupport(UserAuthService users,AdminSessionQueryApi admins){
        this.users=Objects.requireNonNull(users);this.admins=Objects.requireNonNull(admins);
    }

    EvidencePrincipal principal(HttpServletRequest request,String audience,boolean write,boolean command){
        String party=switch(audience){case "c"->"USER";case "merchant"->"MERCHANT";case "admin"->"OPS";default->throw invalid();};
        requireOneAuthorization(request);
        String requestId=command?requestId(request):UUID.randomUUID().toString();
        return resolve(request,party,requestId,trace(request),write);
    }
    void requireCurrent(HttpServletRequest request,EvidencePrincipal original,boolean write){
        var current=resolve(request,original.party(),original.context().requestId(),original.context().traceId(),write);
        if(!original.equals(current))throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Evidence session changed");
    }
    private EvidencePrincipal resolve(HttpServletRequest request,String party,String requestId,String trace,boolean write){
        requireOneAuthorization(request);
        if("OPS".equals(party)){
            try{
                var session=admins.resolveSession(AdminBearerAuthenticationFilter.bearer(request));
                var p=session.principal();
                if(!"ADMIN_WEB".equals(p.audience()))throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Administrator session required");
                return new EvidencePrincipal(new CommandContext(requestId,trace,OperatorType.PLATFORM_OPERATOR,p.operatorId(),"ADMIN_WEB"),
                        p.audience(),p.sessionId(),p.sessionGeneration(),party);
            }catch(AdminAuthFailure failure){throw new ApiException(failure.code(),"Administrator session unavailable");}
        }
        var session=users.resolveSession(CBearerSessionFilter.bearer(request));
        if(write&&!"ACTIVE".equals(session.userStatus()))throw new ApiException("USER_FROZEN","Frozen accounts cannot upload evidence");
        return new EvidencePrincipal(new CommandContext(requestId,trace,OperatorType.USER,session.userId(),"MINIAPP"),
                "MINIAPP",session.sessionId(),0,party);
    }

    static String id(String value){try{IDS.fromApi(value);return value;}catch(RuntimeException invalid){throw invalid();}}
    static String requestId(HttpServletRequest request){
        try{
            var values=request.getHeaders("X-Request-Id");
            if(values==null||Collections.list(values).size()!=1)throw invalid();
            return PublicContractChecks.requireTerminalRequestId(request.getHeader("X-Request-Id"));
        }
        catch(RuntimeException invalid){throw invalid();}
    }
    private static void requireOneAuthorization(HttpServletRequest request){
        var values=request.getHeaders("Authorization");
        if(values==null||Collections.list(values).size()!=1)throw new ApiException(CommonApiCodes.UNAUTHORIZED,"Exactly one evidence session is required");
    }
    static String reason(String json){
        try{
            var root=JSON.readTree(json);
            if(root==null||!root.isObject()||root.size()!=1||root.get("reason")==null||!root.get("reason").isTextual())throw invalid();
            String reason=root.get("reason").textValue();
            if(reason==null||reason.isBlank()||reason.length()>500||reason.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw invalid();
            return reason;
        }catch(RuntimeException invalid){throw invalid();}
    }
    static void noParameters(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    static void noReadBody(HttpServletRequest request){
        noParameters(request);
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw invalid();
    }
    static String token(String token){if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw invalid();return token;}
    static String time(java.time.OffsetDateTime value){return TIME.format(value.toInstant());}
    static String mediaType(PushbackInputStream input,String declared)throws IOException{
        byte[] h=input.readNBytes(8);if(h.length>0)input.unread(h);
        String actual=null;
        if(h.length>=8&&h[0]==(byte)0x89&&h[1]==0x50&&h[2]==0x4e&&h[3]==0x47&&h[4]==0x0d&&h[5]==0x0a&&h[6]==0x1a&&h[7]==0x0a)actual="image/png";
        if(h.length>=3&&h[0]==(byte)0xff&&h[1]==(byte)0xd8&&h[2]==(byte)0xff)actual="image/jpeg";
        if(actual==null||declared!=null&&!declared.isBlank()&&!"application/octet-stream".equals(declared)&&!actual.equals(declared))throw new UnsupportedEvidenceMedia();
        return actual;
    }
    static void privateHeaders(HttpServletResponse response){
        response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");response.setHeader("X-Content-Type-Options","nosniff");
    }
    static String trace(HttpServletRequest request){
        String current=MDC.get(TraceContextFilter.TRACE_MDC_KEY);if(current!=null&&!current.isBlank())return current;
        if(request.getAttribute("aftersaleEvidenceTrace") instanceof String t)return t;
        String generated=UUID.randomUUID().toString();request.setAttribute("aftersaleEvidenceTrace",generated);return generated;
    }
    static Map<String,Object> envelope(Object data,HttpServletRequest request){
        var result=new LinkedHashMap<String,Object>();result.put("code","SUCCESS");result.put("message","ok");
        result.put("data",data);result.put("traceId",trace(request));return result;
    }
    static Map<String,Object> error(int status,String code,String message,HttpServletRequest request,HttpServletResponse response){
        privateHeaders(response);response.setStatus(status);var result=new LinkedHashMap<String,Object>();
        result.put("code",code);result.put("message",message);result.put("data",null);result.put("traceId",trace(request));return result;
    }
    static ApiException invalid(){return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid evidence request");}
    static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Evidence dependency unavailable");}
    static final class UnsupportedEvidenceMedia extends RuntimeException {}
    static final class EvidenceTooLarge extends RuntimeException {}
}
