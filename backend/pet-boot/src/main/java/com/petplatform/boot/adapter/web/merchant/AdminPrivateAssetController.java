package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;
import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "pet.private-assets", name = "enabled", havingValue = "true")
public final class AdminPrivateAssetController {
  private final PrivateAssetApi assets;

  public AdminPrivateAssetController(PrivateAssetApi assets) {
    this.assets = assets;
  }

  @PostMapping(
      value =
          "/api/v1/admin/merchant-applications/{applicationId}/private-assets/{assetId}/read-grants",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> issue(
      @PathVariable String applicationId,
      @PathVariable String assetId,
      @RequestBody String json,
      HttpServletRequest request,
      HttpServletResponse response) {
    onlyParameters(request);
    var body = PrivateAssetHttpRequests.issueGrant(json);
    AdminSessionView session =
        admin(request, "merchant.application.decide", "merchant.identity.reveal");
    IssuedPrivateAssetReadGrant result =
        assets.issueReadGrant(
            new IssuePrivateAssetReadGrantCommand(
                id(assetId),
                id(applicationId),
                body.submissionRevisionId(),
                body.purposeCode(),
                body.reason(),
                session.principal().sessionId(),
                session.principal().sessionGeneration(),
                adminCommand(request, session)));
    if (result == null
        || result.token() == null
        || !result.token().matches("[A-Za-z0-9_-]{32,512}")
        || result.expiresAt() == null
        || result.expiresAt().getNano() % 1_000_000 != 0) {
      throw new ApiException(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE, "private material grant is unavailable");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("readUrl", "/api/v1/admin/private-asset-read-grants/" + result.token());
    data.put("expiresAt", time(result.expiresAt()));
    privateNoStore(response);
    return envelope(data, request);
  }

  @GetMapping("/api/v1/admin/private-asset-read-grants/{token}")
  void consume(@PathVariable String token, HttpServletRequest request, HttpServletResponse response)
      throws java.io.IOException {
    onlyParameters(request);
    if (token == null || !token.matches("[A-Za-z0-9_-]{32,512}")) throw invalid();
    AdminSessionView session =
        admin(request, "merchant.application.decide", "merchant.identity.reveal");
    PrivateAssetContent result =
        assets.consumeReadGrant(
            new ConsumePrivateAssetReadGrantCommand(
                token,
                session.principal().sessionId(),
                session.principal().sessionGeneration(),
                new CommandContext(
                    UUID.randomUUID().toString(),
                    trace(request),
                    OperatorType.PLATFORM_OPERATOR,
                    session.principal().operatorId(),
                    "ADMIN_WEB")));
    byte[] rendered = result.content();
    if (!"image/png".equals(result.mediaType())
        || rendered == null
        || rendered.length < 1
        || result.bytes() != rendered.length
        || result.objectSha256() == null
        || !result.objectSha256().matches("[0-9a-f]{64}")) {
      throw new ApiException(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE, "private material is unavailable");
    }
    privateNoStore(response);
    response.setStatus(200);
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"private-material\"");
    response.setContentType(MediaType.IMAGE_PNG_VALUE);
    response.setContentLengthLong(result.bytes());
    response.getOutputStream().write(rendered);
  }

  private static void privateNoStore(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
  }
}
