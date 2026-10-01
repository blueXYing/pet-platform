package com.petplatform.boot.adapter.web.aftersale;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import com.petplatform.aftersale.api.command.AfterSaleCommandApi;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.*;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.*;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Boundary mocks only; the companion acceptance suite uses real sessions and database. */
class AfterSaleOptionsHttpTest {
    private static final String PATH="/api/v1/c/aftersale-options";
    private final AfterSaleQueryApi queries=mock(AfterSaleQueryApi.class);
    private final AfterSaleCommandApi commands=mock(AfterSaleCommandApi.class);
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new AfterSaleController(commands,queries)).build();
    private static UserAuthService.MiniSessionView session(){return new UserAuthService.MiniSessionView("801","701",Instant.parse("2030-01-01T00:00:00Z"),"138****0000","FROZEN");}
    @Test void exactReadSurfaceDerivesCurrentActorAndNeverNeedsWriteUuid()throws Exception{
        when(queries.options(any())).thenAnswer(i->{CommandContext c=i.getArgument(0);assertThat(c.operatorId()).isEqualTo("701");assertThat(c.operatorType()).isEqualTo(OperatorType.USER);assertThat(c.requestId()).isNull();
            return new Options(List.of(new Option("ISSUE","Service issue")),List.of(new Option("OUTCOME","Requested outcome")));});
        mvc.perform(get(PATH).header("Authorization","Bearer buyer").requestAttr(CBearerSessionFilter.VIEW,session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS")).andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.typeOptions[0].label").value("Service issue")).andExpect(jsonPath("$.data.demandOptions[0].code").value("OUTCOME"))
                .andExpect(jsonPath("$.success").doesNotExist()).andExpect(header().string("Cache-Control","no-store, private"));
        verifyNoInteractions(commands);
    }
    @Test void bodyQueryAndMissingDuplicateAuthorizationNeverReachCatalog()throws Exception{
        for(String query:List.of("?userId=701","?page=1","?page=1&page=1","?","?&&"))
            mvc.perform(get(PATH+query).with(r->{r.setQueryString(query.substring(1));return r;}).header("Authorization","Bearer buyer").requestAttr(CBearerSessionFilter.VIEW,session())).andExpect(status().isBadRequest());
        for(String body:List.of("{}"," ","null"))
            mvc.perform(get(PATH).content(body).header("Authorization","Bearer buyer").requestAttr(CBearerSessionFilter.VIEW,session())).andExpect(status().isBadRequest());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization","Bearer buyer","Bearer other").requestAttr(CBearerSessionFilter.VIEW,session())).andExpect(status().isUnauthorized());
        verifyNoInteractions(queries,commands);
    }
    @Test void incompleteCatalogIsSafe503AndDefaultOffRegistersNoController(){
        new ApplicationContextRunner().withUserConfiguration(AfterSaleController.class)
                .withBean(AfterSaleCommandApi.class,()->commands).withBean(AfterSaleQueryApi.class,()->queries)
                .run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(AfterSaleController.class);});
    }
    @Test void missingCatalogDependencyMapsTo503NoFallback()throws Exception{
        when(queries.options(any())).thenThrow(new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"missing labels"));
        mvc.perform(get(PATH).header("Authorization","Bearer buyer").requestAttr(CBearerSessionFilter.VIEW,session()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(CommonApiCodes.DEPENDENCY_UNAVAILABLE))
                .andExpect(jsonPath("$.data").isEmpty()).andExpect(header().string("Cache-Control","no-store, private"));
    }
}
