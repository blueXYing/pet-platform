package com.petplatform.boot.config;

import com.petplatform.aftersale.biz.application.AfterSalePorts;
import com.petplatform.admin.api.dto.*;
import com.petplatform.admin.api.query.*;
import com.petplatform.common.*;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.user.biz.application.UserAuthService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.web.context.request.*;

/** Contract50 real identity adapter; request-body actor fields alone never authorize a command. */
public final class AfterSaleAuthorityAdapter implements AfterSalePorts.Authority {
    public record SessionIdentity(String audience,String sessionId,long generation,String actorId) {}
    private final UserAuthService users;
    private final AdminSessionQueryApi sessions;
    private final AdminAuthorizationQueryApi admins;
    private final MerchantOrderAuthorityApi merchants;
    public AfterSaleAuthorityAdapter(UserAuthService users,AdminSessionQueryApi sessions,
            AdminAuthorizationQueryApi admins,MerchantOrderAuthorityApi merchants) {
        this.users=Objects.requireNonNull(users);this.sessions=Objects.requireNonNull(sessions);
        this.admins=Objects.requireNonNull(admins);this.merchants=Objects.requireNonNull(merchants);
    }
    public SessionIdentity currentSession(CommandContext c) {
        return currentSession(c,false);
    }
    private SessionIdentity currentSession(CommandContext c,boolean requireActive) {
        try { return resolveCurrentSession(c,requireActive); }
        catch(com.petplatform.admin.biz.application.AdminAuthFailure failure){
            throw new ApiException(failure.code(),"Current administrator session unavailable");
        }
    }
    private SessionIdentity resolveCurrentSession(CommandContext c,boolean requireActive) {
        if(c==null||!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a))throw denied();
        if(c.operatorType()==OperatorType.USER){
            var s=users.resolveSession(CBearerSessionFilter.bearer(a.getRequest()));
            if(requireActive&&!"ACTIVE".equals(s.userStatus())||!s.userId().equals(c.operatorId()))throw denied();
            // MINIAPP sessions have immutable unique IDs, not an ADMIN generation counter.
            // Replacing/revoking the session changes its identity or makes resolveSession fail.
            return new SessionIdentity("MINIAPP",s.sessionId(),0,s.userId());
        }
        if(c.operatorType()==OperatorType.PLATFORM_OPERATOR){
            var s=sessions.resolveSession(AdminBearerAuthenticationFilter.bearer(a.getRequest()));
            var p=s.principal();if(!p.operatorId().equals(c.operatorId())||!"ADMIN_WEB".equals(p.audience()))throw denied();
            return new SessionIdentity(p.audience(),p.sessionId(),p.sessionGeneration(),p.operatorId());
        }
        throw denied();
    }
    @Override public void requireUser(CommandContext c){if(c==null||c.operatorType()!=OperatorType.USER)throw denied();currentSession(c,true);}
    @Override public void requireOwner(CommandContext c,AfterSalePorts.Resource r){
        requireUser(c);merchants.requireOwner(r.merchantId(),r.storeId(),q(c));
    }
    @Override public AfterSalePorts.AdminAuthority requireAdmin(CommandContext c,AfterSalePorts.Resource r,String action){
        if(c==null||c.operatorType()!=OperatorType.PLATFORM_OPERATOR||!Set.of("aftersale.read","aftersale.handle","aftersale.decide").contains(action))throw denied();
        var session=currentSession(c);
        // The mutable resource scope is read from MER under the already-held store guard, not
        // trusted from the case creation snapshot or a client city filter.
        var scope=merchants.requireResourceScope(r.merchantId(),r.storeId(),q(c));
        var decision=admins.check(new AdminActionCheckQuery(session.sessionId(),session.generation(),session.actorId(),action,
                new AdminResourceScope("AFTERSALE",r.afterSaleId()==null?r.orderId():r.afterSaleId(),r.merchantId(),scope.cityCode(),scope.scopeVersion()),
                "AFTERSALE_PROCESSING",AdminActionCheckQuery.CheckPhase.EXECUTE));
        if(!decision.allowed())throw denied();return new AfterSalePorts.AdminAuthority(decision.authzVersion(),scope.scopeVersion());
    }
    @Override public AfterSalePorts.ReadAuthority requireRead(CommandContext c,AfterSalePorts.Resource r){
        if(c==null)throw denied();
        if(c.operatorType()==OperatorType.PLATFORM_OPERATOR){var checked=requireAdmin(c,r,"aftersale.read");
            return new AfterSalePorts.ReadAuthority("OPS",revision(checked.authzVersion(),checked.scopeVersion()));}
        if(c.operatorType()!=OperatorType.USER)throw denied();
        var session=currentSession(c);
        if(session.actorId().equals(r.userId()))return new AfterSalePorts.ReadAuthority("USER",revision("USER",session.sessionId()));
        merchants.requireOwner(r.merchantId(),r.storeId(),q(c));
        var scope=merchants.requireResourceScope(r.merchantId(),r.storeId(),q(c));
        return new AfterSalePorts.ReadAuthority("MERCHANT",revision(session.sessionId(),scope.scopeVersion()));
    }
    public static String revision(String a,String b){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((a.length()+":"+a+b.length()+":"+b).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception invalid){throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Authority revision unavailable");}
    }
    private static QueryContext q(CommandContext c){return new QueryContext(c.traceId(),c.operatorType(),c.operatorId());}
    private static ApiException denied(){return new ApiException(CommonApiCodes.FORBIDDEN,"Current aftersale authority required");}
}
