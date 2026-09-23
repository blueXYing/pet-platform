package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;
import static com.petplatform.boot.config.MerchantPrivateAssetQueryAdapter.MERCHANT_APPLICATION_PURPOSE;
import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantAdmissionApiImpl;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping(value = "/api/v1/c/private-assets", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.private-assets", name = "enabled", havingValue = "true")
public final class CPrivateAssetController {
  static final long MAX_BYTES = 10L * 1024 * 1024;
  private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png");
  /** CCR-W2-API-001 store read: SERVICE_COVER shares the pipeline (31 supplement, MER writer). */
  static final String SERVICE_COVER_PURPOSE = "SERVICE_COVER";
  private static final Set<String> PURPOSES = Set.of(MERCHANT_APPLICATION_PURPOSE, SERVICE_COVER_PURPOSE);
  private final PrivateAssetApi assets;
  private final ObjectProvider<MerchantAdmissionApiImpl> admissions;

  public CPrivateAssetController(
      PrivateAssetApi assets, ObjectProvider<MerchantAdmissionApiImpl> admissions) {
    this.assets = assets;
    this.admissions = admissions;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> upload(
      @RequestParam("purpose") String purpose,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (!(request instanceof MultipartHttpServletRequest multipart)
        || !multipart.getParameterMap().keySet().equals(Set.of("purpose"))
        || multipart.getParameterValues("purpose") == null
        || multipart.getParameterValues("purpose").length != 1
        || !multipart.getMultiFileMap().keySet().equals(Set.of("file"))
        || multipart.getFiles("file").size() != 1
        || !PURPOSES.contains(purpose)
        || file.isEmpty()
        || file.getSize() <= 0
        || file.getSize() > MAX_BYTES) throw invalid();
    String requestId;
    try {
      requestId = PublicContractChecks.requireTerminalRequestId(request.getHeader("X-Request-Id"));
    } catch (RuntimeException badRequestId) {
      throw invalid();
    }
    var session = mini(request);
    if (SERVICE_COVER_PURPOSE.equals(purpose)) requireMerchantMainAccount(request, session);
    UploadPrivateAssetResult result;
    try (var content = new PushbackInputStream(file.getInputStream(), 8)) {
      String mediaType = serverMediaType(content, file.getContentType());
      result =
          assets.upload(
              new UploadPrivateAssetCommand(
                  session.userId(),
                  purpose,
                  mediaType,
                  file.getSize(),
                  content,
                  new com.petplatform.common.CommandContext(
                      requestId,
                      trace(request),
                      com.petplatform.common.OperatorType.USER,
                      session.userId(),
                      "MINIAPP")));
    }
    if (result == null
        || !validId(result.assetId())
        || result.status() != PrivateAssetStatus.READY
        || result.objectSha256() == null
        || !result.objectSha256().matches("[0-9a-f]{64}")
        || !MEDIA_TYPES.contains(result.mediaType())
        || result.bytes() < 1
        || result.bytes() > MAX_BYTES) {
      throw new com.petplatform.common.ApiException(
          com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
          "private asset receipt is unavailable");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("assetId", result.assetId());
    data.put("status", result.status().name());
    data.put("objectSha256", result.objectSha256());
    data.put("mediaType", result.mediaType());
    data.put("bytes", result.bytes());
    response.setStatus(result.created() ? HttpStatus.CREATED.value() : HttpStatus.OK.value());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    return envelope(data, request);
  }

  /**
   * SERVICE_COVER uploads are scoped to merchant main accounts (the user-ruled assignment): the
   * session user must own at least one merchant. Whether that merchant may currently operate is
   * the service write-side gate, not the pipeline's. Missing merchant facts fail closed (503),
   * never silently allow.
   */
  private void requireMerchantMainAccount(HttpServletRequest request, MiniSessionView session) {
    MerchantAdmissionApiImpl admission = admissions.getIfAvailable();
    if (admission == null) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "商家事实暂时不可用");
    }
    MerchantMembershipPageDTO memberships =
        admission.listMemberships(
            new MerchantMembershipQuery(
                1, 1, new QueryContext(trace(request), OperatorType.USER, session.userId())));
    if (memberships == null || memberships.total() < 1) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "仅商家主账号可上传服务封面素材");
    }
  }

  private static String serverMediaType(PushbackInputStream content, String declared)
      throws IOException {
    byte[] header = content.readNBytes(8);
    if (header.length > 0) content.unread(header);
    String actual = null;
    if (header.length >= 8
        && header[0] == (byte) 0x89
        && header[1] == 0x50
        && header[2] == 0x4e
        && header[3] == 0x47
        && header[4] == 0x0d
        && header[5] == 0x0a
        && header[6] == 0x1a
        && header[7] == 0x0a) actual = "image/png";
    if (header.length >= 3
        && header[0] == (byte) 0xff
        && header[1] == (byte) 0xd8
        && header[2] == (byte) 0xff) actual = "image/jpeg";
    if (actual == null) throw new UnsupportedPrivateAssetMediaType();
    if (declared == null || declared.isBlank() || "application/octet-stream".equals(declared)) {
      return actual;
    }
    if (!MEDIA_TYPES.contains(declared) || !declared.equals(actual))
      throw new UnsupportedPrivateAssetMediaType();
    return actual;
  }

  private static boolean validId(String value) {
    try {
      return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  static final class UnsupportedPrivateAssetMediaType extends RuntimeException {}
}
