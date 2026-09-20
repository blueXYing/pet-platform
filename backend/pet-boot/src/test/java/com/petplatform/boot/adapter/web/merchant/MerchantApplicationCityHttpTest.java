package com.petplatform.boot.adapter.web.merchant;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.petplatform.boot.config.*;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MerchantApplicationCityHttpTest {
  @SuppressWarnings("unchecked")
  private static MockMvc mvc(MerchantApplicationCityCatalog catalog) {
    ObjectProvider<MerchantApplicationCityCatalog> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(catalog);
    return MockMvcBuilders.standaloneSetup(new MerchantApplicationCityController(provider))
        .setControllerAdvice(new MerchantHttpExceptionHandler())
        .addFilters(new TraceContextFilter())
        .build();
  }

  private static MiniSessionView session() {
    return new MiniSessionView(
        "901", "501", Instant.now().plusSeconds(60), "138****0000", "ACTIVE");
  }

  @Test
  void returnsConfiguredChengduOnlyForCurrentSession() throws Exception {
    mvc(new MerchantApplicationCityCatalog(
            List.of(new MerchantApplicationCityCatalog.City("chengdu", "成都"))))
        .perform(
            get("/api/v1/c/merchant-application-cities")
                .requestAttr(CBearerSessionFilter.VIEW, session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].cityCode").value("chengdu"))
        .andExpect(jsonPath("$.data.items[0].cityName").value("成都"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void missingSessionAndMissingDirectoryFailClosed() throws Exception {
    MockMvc configured =
        mvc(
            new MerchantApplicationCityCatalog(
                List.of(new MerchantApplicationCityCatalog.City("chengdu", "成都"))));
    configured
        .perform(get("/api/v1/c/merchant-application-cities"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false));
    mvc(new MerchantApplicationCityCatalog(List.of()))
        .perform(
            get("/api/v1/c/merchant-application-cities")
                .requestAttr(CBearerSessionFilter.VIEW, session()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("COMMON_DEPENDENCY_UNAVAILABLE"));
  }

  @Test
  void queryParametersAreRejected() throws Exception {
    mvc(new MerchantApplicationCityCatalog(
            List.of(new MerchantApplicationCityCatalog.City("chengdu", "成都"))))
        .perform(
            get("/api/v1/c/merchant-application-cities?cityCode=chengdu")
                .requestAttr(CBearerSessionFilter.VIEW, session()))
        .andExpect(status().isBadRequest());
  }
}
