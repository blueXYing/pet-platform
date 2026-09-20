package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.petplatform.admin.api.dto.*;
import com.petplatform.boot.config.*;
import com.petplatform.common.ApiException;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PrivateAssetHttpTest {
  private final PrivateAssetApi assets = mock(PrivateAssetApi.class);
  private final MockMvc c =
      MockMvcBuilders.standaloneSetup(new CPrivateAssetController(assets))
          .setControllerAdvice(new MerchantHttpExceptionHandler())
          .addFilters(new TraceContextFilter())
          .build();
  private final MockMvc admin =
      MockMvcBuilders.standaloneSetup(new AdminPrivateAssetController(assets))
          .setControllerAdvice(new MerchantHttpExceptionHandler())
          .addFilters(new TraceContextFilter())
          .build();

  @Test
  void databaseFailureIsSanitizedRetryableAndNeverLeaksSql() throws Exception {
    when(assets.upload(any()))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "private source SQL parameters must not escape"));
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(
                    new MockMultipartFile(
                        "file",
                        "x.png",
                        "image/png",
                        new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}))
                .param("purpose", "MERCHANT_APPLICATION_MATERIAL")
                .requestAttr(CBearerSessionFilter.VIEW, mini("501"))
                .header("X-Request-Id", UUID.randomUUID().toString()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("COMMON_DEPENDENCY_UNAVAILABLE"))
        .andExpect(jsonPath("$.data").isEmpty())
        .andExpect(header().string("Cache-Control", "no-store, private"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQL parameters"))));
  }

  @Test
  void multipartUploadStreamsBoundedBytesForAuthenticatedOwner() throws Exception {
    byte[] source = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    AtomicReference<byte[]> observed = new AtomicReference<>();
    when(assets.upload(any()))
        .thenAnswer(
            invocation -> {
              UploadPrivateAssetCommand command = invocation.getArgument(0);
              observed.set(command.content().readAllBytes());
              return new UploadPrivateAssetResult(
                  "801",
                  true,
                  PrivateAssetStatus.READY,
                  "a".repeat(64),
                  "image/png",
                  source.length);
            });
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(new MockMultipartFile("file", "x.png", "image/png", source))
                .param("purpose", "MERCHANT_APPLICATION_MATERIAL")
                .requestAttr(CBearerSessionFilter.VIEW, mini("501"))
                .header("X-Request-Id", "11111111-1111-1111-1111-111111111111"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.assetId").value("801"))
        .andExpect(jsonPath("$.data.objectSha256").value("a".repeat(64)))
        .andExpect(jsonPath("$.data.bytes").value(8));
    assertArrayEquals(source, observed.get());
    ArgumentCaptor<UploadPrivateAssetCommand> captured =
        ArgumentCaptor.forClass(UploadPrivateAssetCommand.class);
    verify(assets).upload(captured.capture());
    assertEquals("501", captured.getValue().ownerUserId());
    assertEquals("501", captured.getValue().context().operatorId());
    assertEquals("MINIAPP", captured.getValue().context().source());
  }

  @Test
  void uploadRejectsMissingSessionUnknownPartsAndUnsupportedPurpose() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "x.png",
            "image/png",
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(file)
                .param("purpose", "MERCHANT_APPLICATION_MATERIAL")
                .header("X-Request-Id", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized());
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(file)
                .param("purpose", "PUBLIC_ASSET")
                .param("ownerUserId", "999")
                .requestAttr(CBearerSessionFilter.VIEW, mini("501"))
                .header("X-Request-Id", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(assets);
  }

  @Test
  void genericContentTypeIsServerSniffedAndReplayReturns200() throws Exception {
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    when(assets.upload(any()))
        .thenAnswer(
            invocation -> {
              UploadPrivateAssetCommand command = invocation.getArgument(0);
              assertEquals("image/png", command.declaredMediaType());
              assertArrayEquals(png, command.content().readAllBytes());
              return new UploadPrivateAssetResult(
                  "801", false, PrivateAssetStatus.READY, "a".repeat(64), "image/png", 8);
            });
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(new MockMultipartFile("file", "x.png", "application/octet-stream", png))
                .param("purpose", "MERCHANT_APPLICATION_MATERIAL")
                .requestAttr(CBearerSessionFilter.VIEW, mini("501"))
                .header("X-Request-Id", "12111111-1111-1111-1111-111111111111"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.assetId").value("801"));
  }

  @Test
  void explicitMediaTypeMustMatchServerObservedMagic() throws Exception {
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", png))
                .param("purpose", "MERCHANT_APPLICATION_MATERIAL")
                .requestAttr(CBearerSessionFilter.VIEW, mini("501"))
                .header("X-Request-Id", "13111111-1111-1111-1111-111111111111"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(header().string("Cache-Control", "no-store, private"));
    verifyNoInteractions(assets);
  }

  @Test
  void durablyBoundRejectedAssetUsesStable422Code() throws Exception {
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    when(assets.upload(any()))
        .thenThrow(
            new ApiException(
                com.petplatform.thirdparty.api.PrivateAssetApiCodes.ASSET_REJECTED, "上传文件未通过安全检查"));
    c.perform(
            multipart("/api/v1/c/private-assets")
                .file(new MockMultipartFile("file", "x.png", "image/png", png))
                .param("purpose", "MERCHANT_APPLICATION_MATERIAL")
                .requestAttr(CBearerSessionFilter.VIEW, mini("501"))
                .header("X-Request-Id", "14111111-1111-1111-1111-111111111111"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PRIVATE_ASSET_REJECTED"))
        .andExpect(header().string("Cache-Control", "no-store, private"));
  }

  @Test
  void issueAndConsumeCarryCurrentAdminSessionAndNeverReturnObjectUrl() throws Exception {
    when(assets.issueReadGrant(any()))
        .thenReturn(
            new IssuedPrivateAssetReadGrant(
                "abcdefghijklmnopqrstuvwxyzABCDEF",
                OffsetDateTime.parse("2026-09-20T01:02:03.000Z")));
    admin
        .perform(
            post("/api/v1/admin/merchant-applications/101/private-assets/801/read-grants")
                .requestAttr(AdminBearerAuthenticationFilter.VIEW, adminSession())
                .header("X-Request-Id", "22222222-2222-2222-2222-222222222222")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"submissionRevisionId\":\"301\",\"purposeCode\":\"APPLICATION_REVIEW\","
                        + "\"reason\":\"verify submitted identity material\",\"confirmed\":true}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.readUrl")
                .value("/api/v1/admin/private-asset-read-grants/abcdefghijklmnopqrstuvwxyzABCDEF"))
        .andExpect(jsonPath("$.data.expiresAt").value("2026-09-20T01:02:03.000Z"))
        .andExpect(jsonPath("$.data.url").doesNotExist());
    ArgumentCaptor<IssuePrivateAssetReadGrantCommand> issued =
        ArgumentCaptor.forClass(IssuePrivateAssetReadGrantCommand.class);
    verify(assets).issueReadGrant(issued.capture());
    assertEquals("901", issued.getValue().sessionId());
    assertEquals(3, issued.getValue().sessionGeneration());
    assertEquals("701", issued.getValue().context().operatorId());

    byte[] watermarked = new byte[] {1, 2, 3};
    when(assets.consumeReadGrant(any()))
        .thenReturn(new PrivateAssetContent(watermarked, "image/png", "b".repeat(64), 3));
    admin
        .perform(
            get("/api/v1/admin/private-asset-read-grants/abcdefghijklmnopqrstuvwxyzABCDEF")
                .requestAttr(AdminBearerAuthenticationFilter.VIEW, adminSession()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store, private"))
        .andExpect(header().string("Pragma", "no-cache"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().contentType("image/png"))
        .andExpect(content().bytes(watermarked));
    ArgumentCaptor<ConsumePrivateAssetReadGrantCommand> consumed =
        ArgumentCaptor.forClass(ConsumePrivateAssetReadGrantCommand.class);
    verify(assets).consumeReadGrant(consumed.capture());
    assertEquals("901", consumed.getValue().sessionId());
    assertEquals(3, consumed.getValue().sessionGeneration());
  }

  private static MiniSessionView mini(String userId) {
    return new MiniSessionView(
        "601", userId, Instant.now().plusSeconds(60), "138****0000", "ACTIVE");
  }

  private static AdminSessionView adminSession() {
    return new AdminSessionView(
        new AdminSessionPrincipal("ADMIN_WEB", "901", "701", 3),
        OffsetDateTime.now().plusMinutes(5),
        new AdminPermissionSnapshot(
            "701",
            "4:9",
            OffsetDateTime.now(),
            List.of(),
            new AdminDataScope("ALL", List.of(), List.of()),
            List.of("merchant.application.decide", "merchant.identity.reveal")));
  }
}
