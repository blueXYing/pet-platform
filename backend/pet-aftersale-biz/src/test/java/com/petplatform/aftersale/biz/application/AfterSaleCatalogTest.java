package com.petplatform.aftersale.biz.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.command.OrderAfterSaleCommitApi;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi;
import com.petplatform.refund.api.command.RefundAfterSaleCommandApi;
import com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AfterSaleCatalogTest {
    private static Options options(List<Option> types){return new Options(types,List.of(new Option("OUTCOME","Requested outcome")));}
    @Test void invalidWholeCatalogNeverLeaksAnOtherwiseValidHalf() {
        for(var list:List.of(List.<Option>of(),List.of(new Option("TYPE","")),List.of(new Option("TYPE"," leading")),
                List.of(new Option("TYPE","trailing ")),List.of(new Option("TYPE","x".repeat(65))),
                List.of(new Option("TYPE","\uD800")),List.of(new Option("TYPE","\uDC00")),
                List.of(new Option("TYPE","\u00A0Label")),List.of(new Option("TYPE","Label\uFEFF")),
                List.of(new Option("lowercase","Label")),List.of(new Option("TYPE","A"),new Option("TYPE","B")),
                List.of(new Option("Z_TYPE","Z"),new Option("A_TYPE","A")),
                java.util.stream.IntStream.range(0,101).mapToObj(n->new Option("T_"+String.format("%03d",n),"Label")).toList()))
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,assertThrows(ApiException.class,()->AfterSaleCatalog.requireValid(options(list))).code());
    }
    @Test void codePointBoundAllowsPairedEmojiAndOnlyConfiguredCodesCanCreate() {
        String label=new String(Character.toChars(0x1F600)).repeat(64);var catalog=options(List.of(new Option("TYPE",label)));
        assertSame(catalog,AfterSaleCatalog.requireValid(catalog));AfterSaleCatalog.requireCodes(catalog,"TYPE","OUTCOME");
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,()->AfterSaleCatalog.requireCodes(catalog,"UNKNOWN","OUTCOME")).code());
    }
    @Test void optionsRecheckCurrentBuyerAndRejectAuthorityRevisionChanges() {
        var source=mock(DataSource.class);var authority=mock(AfterSalePorts.Authority.class);var reasons=mock(AfterSalePorts.ReasonPolicy.class);
        var service=new AfterSaleService(source,mock(ScheduleCapacityGuardApi.class),mock(SnowflakeIdGenerator.class),
                mock(IntegrationEventPublisher.class),mock(OrderAfterSaleFactsApi.class),mock(OrderAfterSaleCommitApi.class),
                mock(RefundApplicationHistoryFactsApi.class),mock(RefundAfterSaleCommandApi.class),mock(RefundFundingEligibilityFactsApi.class),
                authority,reasons,mock(AfterSalePorts.Moderation.class),new AfterSaleAesProtection(new byte[32]),mock(AfterSalePorts.Assets.class),mock(AfterSalePorts.Tasks.class));
        var buyer=new CommandContext(null,"trace",OperatorType.USER,"123","MINIAPP");
        var catalog=options(List.of(new Option("TYPE","Service issue")));when(reasons.options()).thenReturn(catalog);
        when(authority.requireBuyerRead(buyer)).thenReturn("revision-a","revision-a");assertSame(catalog,service.options(buyer));
        verify(authority,times(2)).requireBuyerRead(buyer);verifyNoInteractions(source);
        when(authority.requireBuyerRead(buyer)).thenReturn("revision-a","revision-b");
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.options(buyer)).code());
        when(authority.requireBuyerRead(buyer)).thenReturn("revision-a").thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED,"revoked"));
        assertEquals(CommonApiCodes.UNAUTHORIZED,assertThrows(ApiException.class,()->service.options(buyer)).code());
        var admin=new CommandContext(null,"trace",OperatorType.PLATFORM_OPERATOR,"456","ADMIN_WEB");
        assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,()->service.options(admin)).code());
    }
}
