package com.petplatform.boot.adapter.web.merchant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.*;
import com.petplatform.order.api.command.MerchantOrderCommandApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi;
import com.petplatform.user.biz.application.UserAuthService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    private final AtomicReference<MerchantOrderQueryApi.StoreOrderListQuery> listed=new AtomicReference<>();
    @SuppressWarnings("unchecked") private MockMvc mvc(String error){
        UserAuthService auth=mock(UserAuthService.class);
        when(auth.resolveSession("valid")).thenReturn(new UserAuthService.MiniSessionView("1","710300",Instant.now().plusSeconds(60),"***","ACTIVE"));
        when(auth.resolveSession("expired")).thenThrow(new ApiException(CommonApiCodes.UNAUTHORIZED,"expired"));
        ObjectProvider<UserAuthService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(auth);
        MerchantOrderCommandApi api=c->{seen.set(c);if(error!=null)throw new ApiException(error,"refresh");
            return new MerchantOrderCommandApi.Receipt(c.orderId(),"9007199254740993",0,c.action(),"PENDING_SERVICE","2026-09-29T00:00:00Z",null);};
        MerchantOrderQueryApi queries=q->{listed.set(q);
            return new PageResult<>(List.of(new MerchantOrderQueryApi.Summary("9007199254740994","2001","PENDING_CONFIRM",
                new BigDecimal("128.00"),OffsetDateTime.parse("2030-01-01T02:00:00Z"),OffsetDateTime.parse("2030-01-01T03:30:00Z"),OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                "E2E体验用户",null,"E2E到店精细洗护","豆豆","DOG","柯基")),1,q.page(),q.pageSize());};
        return MockMvcBuilders.standaloneSetup(new MerchantOrderController(api,queries)).setControllerAdvice(new MerchantStaffExceptionHandler())
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
    @Test void listRequiresSessionCoordinatesAndRendersPageEnvelope() throws Exception {
        var mvc=mvc(null);
        // Session first: the exact list path is bearer-protected like the deeper order routes.
        for(String token:List.of("","expired"))mvc.perform(get("/api/v1/merchant/orders")
                .param("merchantId","710301").param("storeId","710302").header("Authorization",token.isEmpty()?"":"Bearer "+token))
            .andExpect(status().isUnauthorized());
        assertNull(listed.get());
        var listedOk=mvc.perform(get("/api/v1/merchant/orders").param("merchantId","710301").param("storeId","710302")
                .param("displayStatus","PENDING_CONFIRM").param("page","2").param("pageSize","10")
                .header("Authorization","Bearer valid"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.items[0].orderId").value("9007199254740994"))
            .andExpect(jsonPath("$.data.items[0].orderNo").value("2001"))
            .andExpect(jsonPath("$.data.items[0].displayStatus").value("PENDING_CONFIRM"))
            .andExpect(jsonPath("$.data.items[0].payAmount").value("128.00"))
            .andExpect(jsonPath("$.data.items[0].appointmentStart").value("2030-01-01T02:00:00.000Z"))
            .andExpect(jsonPath("$.data.items[0].paidAt").value("2030-01-01T01:00:00.000Z"))
            // CCR-M-ORDER-CARD-001: the six additive card keys ride after the original seven;
            // nullable avatar renders as a JSON null, never a fabricated placeholder.
            .andExpect(jsonPath("$.data.items[0].customerNickname").value("E2E体验用户"))
            .andExpect(jsonPath("$.data.items[0].customerAvatarUrl").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].serviceName").value("E2E到店精细洗护"))
            .andExpect(jsonPath("$.data.items[0].petName").value("豆豆"))
            .andExpect(jsonPath("$.data.items[0].petType").value("DOG"))
            .andExpect(jsonPath("$.data.items[0].petBreed").value("柯基"));
        // CCR-M-ORDER-CARD-001 privacy ruling: no phone — masked, raw or partial — may ever
        // appear anywhere on the list wire, not even as a null key.
        String wire=listedOk.andReturn().getResponse().getContentAsString();
        assertFalse(wire.toLowerCase().contains("phone"),"merchant order list wire must never carry any phone field: "+wire);
        assertEquals("710301",listed.get().merchantId());assertEquals("710302",listed.get().storeId());
        assertEquals("PENDING_CONFIRM",listed.get().displayStatus());
        assertEquals("710300",listed.get().context().operatorId());
        assertEquals(OperatorType.USER,listed.get().context().operatorType());
    }
    @Test void listRejectsUnknownQueryParametersAndBadShapesBeforeBusiness() throws Exception {
        var mvc=mvc(null);
        // Unknown parameter, duplicated parameter, non-decimal coordinate, out-of-range pageSize.
        mvc.perform(get("/api/v1/merchant/orders").param("merchantId","710301").param("storeId","710302")
                .param("status","PENDING_CONFIRM").header("Authorization","Bearer valid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/merchant/orders").param("merchantId","710301").param("merchantId","710301")
                .param("storeId","710302").header("Authorization","Bearer valid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/merchant/orders").param("merchantId","0710301").param("storeId","710302")
                .header("Authorization","Bearer valid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/merchant/orders").param("merchantId","710301").param("storeId","710302")
                .param("pageSize","101").header("Authorization","Bearer valid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/merchant/orders").param("storeId","710302")
                .header("Authorization","Bearer valid")).andExpect(status().isBadRequest());
        assertNull(listed.get());
    }
}
