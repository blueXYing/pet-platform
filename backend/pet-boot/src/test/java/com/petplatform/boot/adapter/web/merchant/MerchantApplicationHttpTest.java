package com.petplatform.boot.adapter.web.merchant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.petplatform.admin.api.dto.*;
import com.petplatform.boot.config.*;
import com.petplatform.merchant.api.command.MerchantApplicationCommandApi;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MerchantApplicationHttpTest {
  MerchantApplicationCommandApi commands = mock(MerchantApplicationCommandApi.class);
  MerchantApplicationQueryApi queries = mock(MerchantApplicationQueryApi.class);
  MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new MerchantApplicationController(commands, queries))
          .setControllerAdvice(new MerchantHttpExceptionHandler())
          .addFilters(new TraceContextFilter())
          .build();
  MockMvc adminMvc =
      MockMvcBuilders.standaloneSetup(new MerchantApplicationAdminController(commands, queries))
          .setControllerAdvice(new MerchantHttpExceptionHandler())
          .addFilters(new TraceContextFilter())
          .build();

  private static MerchantApplicationResult receipt() {
    return new MerchantApplicationResult(
        "101",
        null,
        "201",
        "DRAFT",
        0,
        new RevisionView(
            "301",
            "1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            OffsetDateTime.parse("2026-09-20T01:02:03.000Z")),
        null,
        null,
        null,
        "PENDING");
  }

  private static MiniSessionView session(String user) {
    return new MiniSessionView("901", user, Instant.now().plusSeconds(60), "138****0000", "ACTIVE");
  }

  private static AdminSessionView adminSession(List<String> actions) {
    return new AdminSessionView(
        new AdminSessionPrincipal("ADMIN_WEB", "901", "701", 3),
        OffsetDateTime.now().plusMinutes(5),
        new AdminPermissionSnapshot(
            "701",
            "9",
            OffsetDateTime.now(),
            List.of(),
            new AdminDataScope("ALL", List.of(), List.of()),
            actions));
  }

  @Test
  void createUsesAuthoritativeSessionAndDurableCreatedFlag() throws Exception {
    when(commands.createDraftOutcome(any()))
        .thenReturn(new ApplicationCommandOutcome(receipt(), true));
    mvc.perform(
            post("/api/v1/c/merchant-applications")
                .requestAttr(CBearerSessionFilter.VIEW, session("501"))
                .header("X-Request-Id", "11111111-1111-1111-1111-111111111111")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.applicationId").value("101"))
        .andExpect(jsonPath("$.data.version").value("0"))
        .andExpect(jsonPath("$.data.currentRevisionId").value("301"))
        .andExpect(jsonPath("$.data.applicationNo").value(org.hamcrest.Matchers.nullValue()));
    ArgumentCaptor<CreateMerchantApplicationCommand> captured =
        ArgumentCaptor.forClass(CreateMerchantApplicationCommand.class);
    verify(commands).createDraftOutcome(captured.capture());
    Assertions.assertEquals("501", captured.getValue().context().operatorId());
    Assertions.assertEquals("MINIAPP", captured.getValue().context().source());
  }

  @Test
  void replayReturns200AndUnknownFieldsAreRejected() throws Exception {
    when(commands.createDraftOutcome(any()))
        .thenReturn(new ApplicationCommandOutcome(receipt(), false));
    var request =
        post("/api/v1/c/merchant-applications")
            .requestAttr(CBearerSessionFilter.VIEW, session("501"))
            .header("X-Request-Id", "22222222-2222-2222-2222-222222222222")
            .contentType(MediaType.APPLICATION_JSON);
    mvc.perform(request.content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentRevisionId").value("301"));
    mvc.perform(
            post("/api/v1/c/merchant-applications")
                .requestAttr(CBearerSessionFilter.VIEW, session("501"))
                .header("X-Request-Id", "33333333-3333-3333-3333-333333333333")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerUserId\":\"999\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));
    mvc.perform(
            put("/api/v1/c/merchant-applications/101/draft")
                .requestAttr(CBearerSessionFilter.VIEW, session("501"))
                .header("X-Request-Id", "33333333-3333-3333-3333-333333333334")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"draft\":{}}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/c/merchant-applications")
                .requestAttr(CBearerSessionFilter.VIEW, session("501"))
                .header("X-Request-Id", "33333333-3333-3333-3333-333333333335")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"a\",\"merchantName\":\"b\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingResolvedSessionIs401AndNeverCallsDomain() throws Exception {
    mvc.perform(
            post("/api/v1/c/merchant-applications")
                .header("X-Request-Id", "44444444-4444-4444-4444-444444444444")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false));
    verifyNoInteractions(commands);
  }

  @Test
  void trailingDocumentAndNumericVersionCannotReachTheDomain() throws Exception {
    mvc.perform(
            post("/api/v1/c/merchant-applications")
                .requestAttr(CBearerSessionFilter.VIEW, session("501"))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{} {}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/v1/c/merchant-applications/101/draft")
                .requestAttr(CBearerSessionFilter.VIEW, session("501"))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"draft\":{}}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(commands);
  }

  @Test
  void adminActionComesFromCurrentSessionAndContextCarriesItsGeneration() throws Exception {
    adminMvc
        .perform(
            post("/api/v1/admin/merchant-applications/101/claim")
                .requestAttr(AdminBearerAuthenticationFilter.VIEW, adminSession(List.of()))
                .header("X-Request-Id", "55555555-5555-5555-5555-555555555555")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskVersion\":\"0\"}"))
        .andExpect(status().isForbidden());
    when(commands.claim(any()))
        .thenReturn(
            new ReviewTaskResult("101", "401", "CLAIMED", 1, "701", OffsetDateTime.now(), "301"));
    adminMvc
        .perform(
            post("/api/v1/admin/merchant-applications/101/claim")
                .requestAttr(
                    AdminBearerAuthenticationFilter.VIEW,
                    adminSession(List.of("merchant.application.decide")))
                .header("X-Request-Id", "66666666-6666-6666-6666-666666666666")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskVersion\":\"0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("1"));
    ArgumentCaptor<ClaimMerchantApplicationCommand> captured =
        ArgumentCaptor.forClass(ClaimMerchantApplicationCommand.class);
    verify(commands).claim(captured.capture());
    Assertions.assertEquals(3, captured.getValue().authorization().sessionGeneration());
    Assertions.assertEquals("701", captured.getValue().context().operatorId());
  }
}
