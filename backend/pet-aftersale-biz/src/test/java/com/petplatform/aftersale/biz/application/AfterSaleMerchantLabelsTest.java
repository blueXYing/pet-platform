package com.petplatform.aftersale.biz.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.aftersale.biz.infrastructure.persistence.mapper.AfterSaleWorkflowMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.command.OrderAfterSaleCommitApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi.DisplayLabel;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi;
import com.petplatform.refund.api.command.RefundAfterSaleCommandApi;
import com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.dto.UserStatus;
import com.petplatform.user.api.query.UserQueryApi;
import java.lang.reflect.RecordComponent;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * CCR-M-AFTERSALE-VIEW-001 (approved 2026-11-06) projection boundary, offline mocks only: one
 * page is exactly one batched ORDER snapshot read plus one batched user-basics read, only the
 * nickname crosses from the basics (never the masked phone or avatar), absent/blank facts read
 * null, and any foreign, duplicate or cross-store answer fails closed. The authorization order
 * (labels only after the proven OWNER read) is exercised by the BOOT HTTP acceptance suite.
 */
class AfterSaleMerchantLabelsTest {
    private static final List<String> LABEL_KEYS=List.of("customerNickname","serviceName","petName","petType","petBreed");
    private static final CommandContext OWNER=new CommandContext(null,"trace",OperatorType.USER,"710300","MINIAPP");
    private final DataSource source=mock(DataSource.class);
    private final OrderAfterSaleFactsApi orders=mock(OrderAfterSaleFactsApi.class);
    private final UserQueryApi users=mock(UserQueryApi.class);
    private final AfterSaleService service=new AfterSaleService(source,mock(ScheduleCapacityGuardApi.class),
            mock(SnowflakeIdGenerator.class),mock(IntegrationEventPublisher.class),orders,
            mock(OrderAfterSaleCommitApi.class),mock(RefundApplicationHistoryFactsApi.class),mock(RefundAfterSaleCommandApi.class),
            mock(RefundFundingEligibilityFactsApi.class),mock(AfterSalePorts.Authority.class),mock(AfterSalePorts.ReasonPolicy.class),
            mock(AfterSalePorts.Moderation.class),new AfterSaleAesProtection(new byte[32]),
            mock(AfterSalePorts.Assets.class),mock(AfterSalePorts.Tasks.class),users);

    private static AfterSaleWorkflowMapper.CaseRow row(long id,long order,long user,long store){
        var row=new AfterSaleWorkflowMapper.CaseRow();row.id=id;row.orderId=order;row.userId=user;row.storeId=store;return row;
    }
    private static UserBasicDTO basic(String id,String phoneMasked,String nickname,String avatar){
        return new UserBasicDTO(id,phoneMasked,nickname,UserStatus.ACTIVE,avatar);
    }

    @Test void onePageIsOneBatchedSnapshotReadAndOneBatchedNicknameRead(){
        when(orders.displayLabels(eq(List.of("401","402","403")),eq("601"),any(),same(source))).thenReturn(List.of(
                new DisplayLabel("401","E2E到店精细洗护","豆豆","DOG","柯基"),
                new DisplayLabel("402","猫咪洗澡","  ",null,null)));
        when(users.listUsers(new UserQueryApi.UserBasicsQuery(List.of("701","702")))).thenReturn(List.of(
                basic("701","138****0100","E2E体验用户","https://wx.qlogo.cn/alice.png"),
                basic("702","139****0200","   ",null)));
        var labels=service.merchantLabels(List.of(row(301,401,701,601),row(302,402,702,601),row(303,403,701,601)),"601",OWNER);

        assertEquals(new CaseLabels("E2E体验用户","E2E到店精细洗护","豆豆","DOG","柯基"),labels.get(301L));
        // A blank nickname or snapshot value is never shown as a name: it reads null.
        assertEquals(new CaseLabels(null,"猫咪洗澡",null,null,null),labels.get(302L));
        // An order without any snapshot row reads null for every snapshot field (no fabricated label).
        assertEquals(new CaseLabels("E2E体验用户",null,null,null,null),labels.get(303L));
        assertEquals(List.of(301L,302L,303L),List.copyOf(labels.keySet()));

        // Exactly one batched read per foreign domain, in SYSTEM context on the caller's own source.
        verify(orders,times(1)).displayLabels(any(),any(),argThat(q->q.operatorType()==OperatorType.SYSTEM&&q.operatorId()==null),same(source));
        verify(users,times(1)).listUsers(any());
        // Only the nickname crosses from the user basics: the masked phone and the avatar never do.
        for(var label:labels.values())for(RecordComponent component:CaseLabels.class.getRecordComponents()){
            Object value=invoke(component,label);
            assertFalse(value instanceof String text&&(text.contains("****")||text.startsWith("https://")),component.getName()+"="+value);
        }
    }

    @Test void emptyPageNeedsNoForeignRead(){
        assertTrue(service.merchantLabels(List.of(),"601",OWNER).isEmpty());
        verifyNoInteractions(orders,users);
    }

    @Test void foreignDuplicateAndCrossStoreAnswersFailClosed(){
        // A row of another store never rides a store-scoped label read, not even partially.
        assertUnavailable(()->service.merchantLabels(List.of(row(301,401,701,601),row(302,402,702,602)),"601",OWNER));
        verifyNoInteractions(orders,users);

        var rows=List.of(row(301,401,701,601));
        when(users.listUsers(any())).thenReturn(List.of(basic("701",null,"E2E体验用户",null)));
        when(orders.displayLabels(any(),any(),any(),any())).thenReturn(List.of(new DisplayLabel("499","他店服务",null,null,null)));
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));
        when(orders.displayLabels(any(),any(),any(),any())).thenReturn(List.of(new DisplayLabel("401","A",null,null,null),new DisplayLabel("401","B",null,null,null)));
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));
        when(orders.displayLabels(any(),any(),any(),any())).thenReturn(null);
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));

        when(orders.displayLabels(any(),any(),any(),any())).thenReturn(List.of());
        when(users.listUsers(any())).thenReturn(List.of(basic("799",null,"别人",null)));
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));
        when(users.listUsers(any())).thenReturn(List.of(basic("701",null,"甲",null),basic("701",null,"乙",null)));
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));
        when(users.listUsers(any())).thenReturn(null);
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));

        // A known dependency failure is propagated unchanged (no silent empty-label fallback).
        when(users.listUsers(any())).thenThrow(new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"basics down"));
        assertUnavailable(()->service.merchantLabels(rows,"601",OWNER));
    }

    @Test void absentAccountReadsNullNicknameWhileSnapshotsStayFactual(){
        when(orders.displayLabels(any(),eq("601"),any(),same(source))).thenReturn(List.of(new DisplayLabel("401","E2E到店精细洗护","豆豆","DOG",null)));
        when(users.listUsers(any())).thenReturn(List.of());
        var labels=service.merchantLabels(List.of(row(301,401,701,601)),"601",OWNER);
        assertEquals(new CaseLabels(null,"E2E到店精细洗护","豆豆","DOG",null),labels.get(301L));
    }

    @Test void recordsCarryNoPhoneShapedComponentAndPreCcrShapesCarryNoLabels(){
        assertEquals(LABEL_KEYS,Arrays.stream(CaseLabels.class.getRecordComponents()).map(RecordComponent::getName).toList());
        for(Class<?> type:List.of(CaseLabels.class,CaseSummary.class,CaseView.class,DisplayLabel.class))
            for(RecordComponent component:type.getRecordComponents()){
                String name=component.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("phone")||name.contains("mobile")||name.contains("avatar")||name.equals("userid")||name.contains("health"),type.getSimpleName()+"."+component.getName());
            }
        assertNull(new CaseSummary("1","2","3","4","PENDING","0","VERIFIED","T","D",null,"2030-01-01T00:00:00Z","2030-01-08T00:00:00Z").labels());
        assertNull(new CaseView("1","2","PENDING","0","VERIFIED","T","D","A description of the case",null,"2030-01-01T00:00:00Z",
                "2030-01-08T00:00:00Z",null,null,null,null,"a".repeat(64),List.of(),null,null,null,null,List.of()).labels());
    }

    private static Object invoke(RecordComponent component,Object target){
        try{return component.getAccessor().invoke(target);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action){
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,assertThrows(ApiException.class,action).code());
    }
}
