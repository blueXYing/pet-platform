package com.petplatform.merchant.biz.application;

import com.petplatform.common.*;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.AdminAuthorization;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableCommand;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableReceipt;
import com.petplatform.merchant.biz.application.AdminDispositionAuthorizationPort.Check;
import com.petplatform.merchant.biz.application.AdminDispositionAuthorizationPort.Decision;
import com.petplatform.merchant.biz.infrastructure.persistence.AdminMerchantDispositionStore;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.AdminMerchantDispositionMapper.ActionRow;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.AdminMerchantDispositionMapper.MerchantRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract-63 v0.2 merchant disable kernel (HTTP10 §5.6 draft; user ruling 2026-10-15, doc 33).
 * SSOT §15: the operator may take a merchant offline immediately even with in-flight orders —
 * in-flight work never blocks the action and is never cancelled by it. The write reuses the
 * existing merchant status vocabulary (ACTIVE → OFFLINE) so every existing consumer gets the
 * semantics from the facts they already read: booking eligibility stops new orders
 * (MerchantOrderEligibilityPolicy), while order/refund/staff kernels admit OFFLINE merchants for
 * in-flight work. One commit writes the CAS update and the append-only merchant_governance_action
 * audit row bound to the terminal requestId (replay returns the stored receipt; a different
 * payload under the same requestId conflicts) after re-proving the ADMIN_WEB session, the
 * merchant.disable action and the resource scope. No event by design (same as the service
 * force-offline precedent): consumers read the authoritative status facts.
 */
public final class AdminMerchantDispositionService implements AdminMerchantDispositionApi {

    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final AdminDispositionAuthorizationPort.MerchantDispositionAuthorizationPort authorization;
    private final AdminMerchantDispositionStore store;
    private final SnowflakeIdGenerator ids;
    private final java.time.Clock clock;
    private final TransactionTemplate tx;

    public AdminMerchantDispositionService(DataSource source, SnowflakeIdGenerator ids,
            AdminDispositionAuthorizationPort.MerchantDispositionAuthorizationPort authorization) {
        this(source, ids, authorization, java.time.Clock.systemUTC());
    }

    public AdminMerchantDispositionService(DataSource source, SnowflakeIdGenerator ids,
            AdminDispositionAuthorizationPort.MerchantDispositionAuthorizationPort authorization, java.time.Clock clock) {
        Objects.requireNonNull(source);
        this.ids=Objects.requireNonNull(ids);
        this.authorization=Objects.requireNonNull(authorization);
        this.clock=Objects.requireNonNull(clock);
        this.store=new AdminMerchantDispositionStore(source);
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public DisableReceipt disable(DisableCommand c) {
        noOuter();
        validate(c);
        String requestId=c.context().requestId();
        var replayed=store.mapper().selectByRequest(requestId);
        if(replayed!=null&&!replayed.isEmpty())return receipt(replayed.getFirst());
        try {
            return tx.execute(s -> {
                var rows=store.mapper().selectByRequest(requestId);
                if(rows!=null&&!rows.isEmpty())return receipt(rows.getFirst());
                MerchantRow m=store.mapper().selectMerchantForUpdate(IDS.fromApi(c.merchantId()));
                if(m==null)throw notFound();
                Decision decision=authorization.check(new Check(
                    c.authorization().sessionId(),c.authorization().sessionGeneration(),
                    c.context().operatorId(),"merchant.disable","MERCHANT",
                    c.merchantId(),c.merchantId(),Long.toString(m.version),"MERCHANT_DISABLE"));
                requireDecision(decision);
                if(!"ACTIVE".equals(m.status))throw conflict("COMMON_CONFLICT");
                OffsetDateTime now=OffsetDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),ZoneOffset.UTC);
                if(store.mapper().disableMerchant(m.id,utc(now))!=1)throw conflict("COMMON_CONFLICT");
                store.mapper().insertAction(ids.nextId(),m.id,c.reason(),m.status,m.version,
                    IDS.fromApi(c.context().operatorId()),utc(now),decision.authzVersion(),
                    requestId,c.context().traceId(),utc(now));
                return new DisableReceipt(c.merchantId(),"OFFLINE",now);
            });
        } catch(ApiException known) { throw known; }
        catch(CannotAcquireLockException busy) { throw conflict("COMMON_CONFLICT"); }
        catch(RuntimeException failure) { throw unavailable(); }
    }

    private static DisableReceipt receipt(ActionRow row) {
        return new DisableReceipt(IDS.toApi(row.merchantId),"OFFLINE",row.actedAt.atOffset(ZoneOffset.UTC));
    }

    private static void requireDecision(Decision decision) {
        if(decision==null||decision.checkedAt()==null||decision.authzVersion()==null||decision.authzVersion().isBlank())
            throw unavailable();
        if(!decision.allowed()) {
            if("RESOURCE_SCOPE_DENIED".equals(decision.reasonCode()))throw notFound();
            throw new ApiException(CommonApiCodes.FORBIDDEN,"无权执行该操作");
        }
    }

    private static void validate(DisableCommand c) {
        try {
            if(c==null||c.context()==null||c.authorization()==null)throw invalid();
            if(c.context().operatorType()!=OperatorType.PLATFORM_OPERATOR
                ||c.context().operatorId()==null||c.context().operatorId().isBlank())throw invalid();
            PublicContractChecks.requireTerminalRequestId(c.context().requestId());
            if(c.context().traceId()==null||c.context().traceId().isBlank())throw invalid();
            if(!c.merchantId().matches("[1-9][0-9]{0,18}")||Long.parseLong(c.merchantId())<=0)throw invalid();
            Long.parseLong(c.context().operatorId());
            String reason=c.reason();
            if(reason==null||reason.isBlank())throw invalid();
            int count=reason.codePointCount(0,reason.length());
            if(count<10||count>500||reason.codePoints().anyMatch(cp -> cp>=0xD800&&cp<=0xDFFF))throw invalid();
            if(c.authorization().sessionId()==null||c.authorization().sessionId().isBlank()
                ||c.authorization().sessionGeneration()<0)throw new ApiException(CommonApiCodes.UNAUTHORIZED,"admin authorization reference is required");
        } catch(ApiException known) { throw known; }
        catch(RuntimeException bad) { throw invalid(); }
    }

    private static java.time.LocalDateTime utc(OffsetDateTime t) { return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    private static void noOuter() {
        if(TransactionSynchronizationManager.isActualTransactionActive()
            ||TransactionSynchronizationManager.isSynchronizationActive())throw unavailable();
    }
    private static ApiException invalid() { return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid merchant disable input"); }
    private static ApiException conflict(String code) { return new ApiException(code,"商家下线与当前状态冲突，请刷新后核对"); }
    private static ApiException notFound() { return new ApiException(CommonApiCodes.NOT_FOUND,"商家不存在"); }
    private static ApiException unavailable() { return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"merchant disable unavailable"); }
}
