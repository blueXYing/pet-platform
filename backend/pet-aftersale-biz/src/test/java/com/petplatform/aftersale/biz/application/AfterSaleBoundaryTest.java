package com.petplatform.aftersale.biz.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.petplatform.common.*;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi.*;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.command.OrderAfterSaleCommitApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi;
import com.petplatform.refund.api.command.RefundAfterSaleCommandApi;
import com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AfterSaleBoundaryTest {
    private final DataSource source=mock(DataSource.class);
    private final AfterSalePorts.Authority authority=mock(AfterSalePorts.Authority.class);
    private final OrderAfterSaleFactsApi orders=mock(OrderAfterSaleFactsApi.class);
    private final RefundAfterSaleCommandApi refunds=mock(RefundAfterSaleCommandApi.class);
    private final AfterSaleService service=new AfterSaleService(source,mock(ScheduleCapacityGuardApi.class),
            mock(SnowflakeIdGenerator.class),mock(IntegrationEventPublisher.class),orders,
            mock(OrderAfterSaleCommitApi.class),mock(RefundApplicationHistoryFactsApi.class),refunds,
            mock(RefundFundingEligibilityFactsApi.class),authority,mock(AfterSalePorts.ReasonPolicy.class),
            mock(AfterSalePorts.Moderation.class),new AfterSaleAesProtection(new byte[32]),
            mock(AfterSalePorts.Assets.class),mock(AfterSalePorts.Tasks.class));
    private static final CommandContext USER=new CommandContext("request-001","trace",OperatorType.USER,"123","MINIAPP");
    private static final CommandContext ADMIN=new CommandContext("request-002","trace",OperatorType.PLATFORM_OPERATOR,"456","ADMIN");

    @Test void arbitraryPrecisionMoneyCannotReachPersistenceOrRefunds() {
        var failure=assertThrows(ApiException.class,()->service.decide(new Decide(ADMIN,"100","0","PARTIAL_REFUND",new BigDecimal("0.001"),"A justified decision")));
        assertEquals("AFTERSALE_AMOUNT_INVALID",failure.code());verifyNoInteractions(source,orders,refunds,authority);
    }
    @Test void nonRefundDecisionCannotSmuggleAnAmount() {
        var failure=assertThrows(ApiException.class,()->service.decide(new Decide(ADMIN,"100","0","RESERVICE",new BigDecimal("1.00"),"Arrange a service")));
        assertEquals("AFTERSALE_AMOUNT_INVALID",failure.code());verifyNoInteractions(source,orders,refunds,authority);
    }
    @Test void duplicateAssetIdsAreRejectedBeforeAdmission() {
        var failure=assertThrows(ApiException.class,()->service.create(new Create(USER,"100","QUALITY","REFUND","A description with sufficient length",null,List.of("12","12"),null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,failure.code());verifyNoInteractions(source,orders,refunds,authority);
    }
    @Test void actorCannotClaimSystemCreation() {
        var fake=new CommandContext("request-003","trace",OperatorType.SYSTEM,null,"ASYNC_TASK");
        var failure=assertThrows(ApiException.class,()->service.create(new Create(fake,"100","QUALITY","REFUND","A description with sufficient length",null,List.of(),null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,failure.code());verifyNoInteractions(source,orders,refunds,authority);
    }
    @Test void malformedRequestAndIdsRemainClientErrors() {
        var malformed=new CommandContext("bad\nrequest","trace",OperatorType.USER,"123","MINIAPP");
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->service.create(new Create(malformed,"100","QUALITY","REFUND","A valid description",null,List.of(),null))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->service.create(new Create(USER,"001","QUALITY","REFUND","A valid description",null,List.of(),null))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->service.decide(new Decide(ADMIN,"100","0",null,null,"A justified decision"))).code());
        verifyNoInteractions(source,orders,refunds,authority);
    }
    @Test void purposeAndIntegrityBindProtectedValues() {
        var protector=new AfterSaleAesProtection(new byte[32]);var bytes="private evidence".getBytes(StandardCharsets.UTF_8);
        var a=protector.protect("EVIDENCE_BATCH:1",bytes);var b=protector.protect("EVIDENCE_BATCH:1",bytes);
        assertFalse(java.util.Arrays.equals(a,b));assertArrayEquals(bytes,protector.reveal("EVIDENCE_BATCH:1",a));
        assertThrows(IllegalStateException.class,()->protector.reveal("EVIDENCE_BATCH:2",a));
        a[a.length-1]^=1;assertThrows(IllegalStateException.class,()->protector.reveal("EVIDENCE_BATCH:1",a));
    }
    @Test void paginatedReadsRejectUnboundedOrUnknownFiltersBeforePersistence() {
        for(var q:List.of(new ListQuery(0,20,null,null),new ListQuery(10001,20,null,null),
                new ListQuery(1,51,null,null),new ListQuery(1,0,null,null),
                new ListQuery(1,20,"UNKNOWN",null),new ListQuery(1,20,null,"001"))) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->service.listMine(USER,q)).code());
        }
        verifyNoInteractions(source,orders,refunds,authority);
    }
    @Test void explicitRoutesCannotPromoteTheActorOrFallBackToGenericAccess() {
        var q=new ListQuery(null,null,null,null);assertEquals(1,q.page());assertEquals(20,q.pageSize());
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.listMine(ADMIN,q)).code());
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.listForStore(USER,RouteParty.OPS,"1","2",q)).code());
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.listForStore(USER,RouteParty.USER,"1","2",q)).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->service.getCase(USER,"100",null)).code());
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.getCase(ADMIN,"100",RouteParty.MERCHANT)).code());
        var command=new SubmitEvidence(USER,"100","0",null,"Enough detail for evidence",List.of());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->service.submitEvidence(command,null)).code());
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.submitEvidence(command,RouteParty.OPS)).code());
        verifyNoInteractions(source,orders,refunds,authority);
    }
}
