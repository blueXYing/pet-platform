package com.petplatform.boot.adapter.web.merchant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.*;
import com.petplatform.order.api.command.MerchantOrderCommandApi;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
/** Adapter/filter boundary test; business transactions are covered by real MySQL acceptance. */
class MerchantOrderHttpTest {
    private final AtomicReference<MerchantOrderCommandApi.Command> seen=new AtomicReference<>();
    @SuppressWarnings("unchecked") private MockMvc mvc(String error){
        UserAuthService auth=mock(UserAuthService.class);
        when(auth.resolveSession("valid")).thenReturn(new UserAuthService.MiniSessionView("1","710300",Instant.now().plusSeconds(60),"***","ACTIVE"));
        when(auth.resolveSession("expired")).thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED,"expired"));
        ObjectProvider<UserAuthService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(auth);
        MerchantOrderCommandApi api=c->{seen.set(c);if(error!=null)throw new ApiException(error,"refresh");
            return new MerchantOrderCommandApi.Receipt(c.orderId(),"9007199254740993",0,c.action(),"PENDING_SERVICE","2026-09-29T00:00:00Z",null);};
        return MockMvcBuilders.standaloneSetup(new MerchantOrderController(api)).setControllerAdvice(new MerchantStaffExceptionHandler())
            .addFilters(new CBearerSessionFilter(provider)).build();
    }
    @Test void onlyValidBearerCanReachMerchantOrderCommands() throws Exception {
        var mvc=mvc(null);
        for(String token:List.of("","expired"))mvc.perform(post("/api/v1/merchant/orders/123/confirm")
            .header("Authorization",token.isEmpty()?"":"Bearer "+token).header("X-Request-Id",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfirmRound\":0}")).andExpect(status().isUnauthorized());
        assertNull(seen.get());
        mvc.perform(post("/api/v1/merchant/orders/9007199254740994/confirm").header("Authorization","Bearer valid")
            .header("X-Request-Id",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfirmRound\":0}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.orderId").value("9007199254740994")).andExpect(jsonPath("$.data.decisionId").value("9007199254740993"));
        assertEquals("710300",seen.get().context().operatorId());assertEquals(OperatorType.USER,seen.get().context().operatorType());
    }
    @Test void duplicateUnknownNullAndWrongRoundFieldsAreRejectedBeforeBusiness() throws Exception {
        var mvc=mvc(null);
        for(String body:List.of("{}","{\"expectedConfirmRound\":2}","{\"expectedConfirmRound\":0.0}","{\"expectedConfirmRound\":0,\"internalNote\":null}",
            "{\"expectedConfirmRound\":0,\"expectedConfirmRound\":0}","{\"expectedConfirmRound\":0,\"operatorId\":\"999\"}"))
            mvc.perform(post("/api/v1/merchant/orders/123/confirm").header("Authorization","Bearer valid").header("X-Request-Id",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertNull(seen.get());
    }
    @Test void rejectionBindsReasonAndRequestIdAndDeadlineUses409() throws Exception {
        var mvc=mvc("ORDER_CONFIRM_DEADLINE_PASSED");String key=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/merchant/orders/123/reject").header("Authorization","Bearer valid").header("X-Request-Id",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfirmRound\":0,\"reasonCode\":\"OTHER\",\"reasonText\":\"cannot attend\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_CONFIRM_DEADLINE_PASSED"));
        assertEquals(key,seen.get().context().requestId());assertEquals("cannot attend",seen.get().reasonText());
    }
}
