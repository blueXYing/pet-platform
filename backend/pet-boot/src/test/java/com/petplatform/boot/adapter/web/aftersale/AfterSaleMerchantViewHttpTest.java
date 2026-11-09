package com.petplatform.boot.adapter.web.aftersale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * CCR-M-AFTERSALE-VIEW-001 (approved 2026-11-06) adapter boundary with mocked queries: merchant
 * routes carry exactly five flat nullable display keys (no nested {@code labels} key, no phone
 * key), C/admin routes keep their exact pre-CCR key sets even if a label ever leaked into the
 * query result, and forbidden/absent answers keep the same safe envelope without any label. The
 * real OWNER/store projection is covered by AfterSaleMerchantViewHttpAcceptanceTest.
 */
class AfterSaleMerchantViewHttpTest {
    private static final List<String> LABEL_KEYS=List.of("customerNickname","serviceName","petName","petType","petBreed");
    private static final Set<String> SUMMARY_KEYS=Set.of("afterSaleId","orderId","merchantId","storeId","status","version",
            "sourceStage","typeCode","demandCode","requestedAmount","createdAt","deadline");
    private static final Set<String> DETAIL_KEYS=Set.of("afterSaleId","orderId","status","version","sourceStage","typeCode",
            "demandCode","description","requestedAmount","createdAt","deadline","supplementRequestId","supplementTarget",
            "supplementDeadline","supplementReason","finalSetVersion","priorFinalCaseIds","newProblemStatement","decisionType",
            "refundAmount","decisionReason","evidence");
    private static final CaseLabels LABELS=new CaseLabels("E2E体验用户","E2E到店精细洗护","豆豆","DOG","柯基");
    private final AfterSaleQueryApi queries=mock(AfterSaleQueryApi.class);
    private final AfterSaleCommandApi commands=mock(AfterSaleCommandApi.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new AfterSaleController(commands,queries)).build();
    private final JsonMapper json=JsonMapper.builder().build();

    private static UserAuthService.MiniSessionView session(){return new UserAuthService.MiniSessionView("801","701",Instant.parse("2030-01-01T00:00:00Z"),"138****0000","ACTIVE");}
    private static CaseView view(CaseLabels labels){
        return new CaseView("301","401","PENDING","0","VERIFIED","SERVICE_QUALITY","REFUND","A verified service problem description",
                new BigDecimal("60.00"),"2030-01-01T00:00:00Z","2030-01-08T00:00:00Z",null,null,null,null,"a".repeat(64),List.of(),
                null,null,null,null,List.of(new EvidenceBatch("501","USER","A verified service problem description",null,"2030-01-01T00:00:00Z",List.of())),labels);
    }
    private static CaseSummary summary(String id,CaseLabels labels){
        return new CaseSummary(id,"401","511","611","PENDING","0","VERIFIED","SERVICE_QUALITY","REFUND",new BigDecimal("60.00"),
                "2030-01-01T00:00:00Z","2030-01-08T00:00:00Z",labels);
    }
    private ResultActions read(String path)throws Exception{
        return mvc.perform(get(path).header("Authorization","Bearer current").requestAttr(CBearerSessionFilter.VIEW,session()));
    }
    private static String wire(ResultActions actions)throws Exception{return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);}
    @SuppressWarnings("unchecked") private Map<String,Object> data(String wire){return (Map<String,Object>)json.readValue(wire,Map.class).get("data");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    private static void noPhone(String wire){
        assertFalse(wire.toLowerCase(Locale.ROOT).contains("phone"),wire);assertFalse(wire.toLowerCase(Locale.ROOT).contains("mobile"),wire);
        assertFalse(wire.contains("138****0000"),wire);assertFalse(wire.contains("\"labels\""),wire);
    }

    @Test void merchantDetailCarriesFiveFlatKeysAndNoPhone()throws Exception{
        when(queries.getCase(any(),eq("301"),eq(RouteParty.MERCHANT))).thenReturn(view(LABELS));
        String wire=wire(read("/api/v1/merchant/aftersales/301").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store, private"))
                .andExpect(jsonPath("$.data.customerNickname").value("E2E体验用户"))
                .andExpect(jsonPath("$.data.serviceName").value("E2E到店精细洗护"))
                .andExpect(jsonPath("$.data.petName").value("豆豆"))
                .andExpect(jsonPath("$.data.petType").value("DOG"))
                .andExpect(jsonPath("$.data.petBreed").value("柯基"))
                .andExpect(jsonPath("$.data.labels").doesNotExist()));
        var expected=new HashSet<>(DETAIL_KEYS);expected.addAll(LABEL_KEYS);
        assertEquals(expected,data(wire).keySet());noPhone(wire);
        // Evidence, version and status keep their exact pre-CCR rendering.
        assertEquals("0",data(wire).get("version"));assertEquals(1,((List<?>)data(wire).get("evidence")).size());
        verify(queries).getCase(argThat(c->c.operatorType()==OperatorType.USER&&"701".equals(c.operatorId())),eq("301"),eq(RouteParty.MERCHANT));
        verifyNoInteractions(commands);
    }

    @Test void merchantListItemsShareTheSameFiveKeysWithExplicitNulls()throws Exception{
        when(queries.listForStore(any(),eq(RouteParty.MERCHANT),eq("511"),eq("611"),any())).thenReturn(new CasePage(1,20,3,List.of(
                summary("301",LABELS),summary("302",new CaseLabels(null,"E2E到店精细洗护",null,null,null)),summary("303",null))));
        String wire=wire(read("/api/v1/merchant/aftersales?merchantId=511&storeId=611").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerNickname").value("E2E体验用户"))
                .andExpect(jsonPath("$.data.items[1].customerNickname").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[1].serviceName").value("E2E到店精细洗护"))
                .andExpect(jsonPath("$.data.items[1].petName").value(Matchers.nullValue())));
        var expected=new HashSet<>(SUMMARY_KEYS);expected.addAll(LABEL_KEYS);
        var rendered=items(data(wire));assertEquals(3,rendered.size());
        for(var item:rendered)assertEquals(expected,item.keySet());
        // A merchant item whose labels were not projected still renders the five keys as explicit nulls.
        for(String key:LABEL_KEYS)assertNull(rendered.get(2).get(key),key);
        noPhone(wire);
    }

    @Test void consumerAndAdminFacesKeepTheExactPreCcrKeySetsEvenIfLabelsLeak()throws Exception{
        when(queries.getCase(any(),eq("301"),eq(RouteParty.USER))).thenReturn(view(LABELS));
        when(queries.listMine(any(),any())).thenReturn(new CasePage(1,20,1,List.of(summary("301",LABELS))));
        String detail=wire(read("/api/v1/c/aftersales/301").andExpect(status().isOk()));
        assertEquals(DETAIL_KEYS,data(detail).keySet());
        String page=wire(read("/api/v1/c/aftersales").andExpect(status().isOk()));
        assertEquals(SUMMARY_KEYS,items(data(page)).get(0).keySet());
        for(String wire:List.of(detail,page)){for(String key:LABEL_KEYS)assertFalse(wire.contains(key),key);assertFalse(wire.contains("E2E体验用户"));}
        // The admin face shares the same renderer gate (route party OPS never carries the keys).
        assertEquals(DETAIL_KEYS,AfterSaleHttpSupport.detail(view(LABELS),RouteParty.OPS).keySet());
        @SuppressWarnings("unchecked") var opsItems=(List<Map<String,Object>>)AfterSaleHttpSupport.page(new CasePage(1,20,1,List.of(summary("301",LABELS))),RouteParty.OPS).get("items");
        assertEquals(SUMMARY_KEYS,opsItems.get(0).keySet());
        assertEquals(DETAIL_KEYS,AfterSaleHttpSupport.detail(view(LABELS),RouteParty.USER).keySet());
    }

    @Test void forbiddenAndAbsentAnswersKeepTheSafeEnvelopeWithoutAnyLabel()throws Exception{
        when(queries.getCase(any(),eq("302"),eq(RouteParty.MERCHANT))).thenThrow(new ApiException(CommonApiCodes.FORBIDDEN,"foreign store"));
        when(queries.getCase(any(),eq("303"),eq(RouteParty.MERCHANT))).thenThrow(new ApiException("AFTERSALE_NOT_FOUND","absent"));
        when(queries.listForStore(any(),eq(RouteParty.MERCHANT),eq("511"),eq("612"),any())).thenThrow(new ApiException(CommonApiCodes.FORBIDDEN,"foreign store"));
        for(var probe:List.of(Map.entry("/api/v1/merchant/aftersales/302",403),Map.entry("/api/v1/merchant/aftersales/303",404),
                Map.entry("/api/v1/merchant/aftersales?merchantId=511&storeId=612",403))){
            String wire=wire(read(probe.getKey()).andExpect(status().is(probe.getValue())).andExpect(jsonPath("$.data").isEmpty()));
            for(String key:LABEL_KEYS)assertFalse(wire.contains(key),probe.getKey()+" "+key);
            assertFalse(wire.contains("E2E体验用户"));noPhone(wire);
        }
        // Missing session never reaches the query (401 before any projection).
        mvc.perform(get("/api/v1/merchant/aftersales/301")).andExpect(status().isUnauthorized());
        verify(queries,never()).getCase(any(),eq("301"),any());
    }
}
