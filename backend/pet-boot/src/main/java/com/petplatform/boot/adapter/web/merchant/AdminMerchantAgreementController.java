package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.apiimpl.MerchantAgreementApiImpl;
import com.petplatform.merchant.biz.application.MerchantAgreementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 运营端协议管理（2026-11-20 用户裁决）：纯文本协议的版本历史与发布。发布即切换当前
 * 指针，小程序签署侧实时读到新版；版本链松耦合——存量签约不受影响、在途签署不会因
 * 版本漂移被拒（签署以盖章时刻的当前版本为准）。
 *
 * <p>权限沿用 merchant.application.decide（运营对商家域的裁决权限；V1 单运营 RBAC 下
 * 与协议发布语义兼容，后续拆分专属动作码时再行登记）。
 */
@RestController
@RequestMapping(value = "/api/v1/admin/merchant-agreements", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.private-assets", name = "enabled", havingValue = "true")
public final class AdminMerchantAgreementController {
  private final MerchantAgreementApiImpl agreements;

  public AdminMerchantAgreementController(MerchantAgreementApiImpl agreements) {
    this.agreements = agreements;
  }

  @GetMapping
  Map<String, Object> list(HttpServletRequest req) {
    onlyParameters(req);
    admin(req, "merchant.application.decide");
    // 只消费商家模块的应用层投影（AgreementVersionView），不触碰其持久化实体（ARCH-002/ARCH-004）。
    List<MerchantAgreementService.AgreementVersionView> versions =
        agreements.listAgreementVersions();
    List<Map<String, Object>> items = new ArrayList<>();
    for (MerchantAgreementService.AgreementVersionView v : versions) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("versionId", String.valueOf(v.versionId()));
      row.put("agreementVersion", v.agreementVersion());
      row.put("content", v.content());
      row.put("contentSha256", v.contentSha256());
      // ISO-8601 UTC 时刻（形如 2026-11-20T12:34:56.123Z），由前端换算成本地时间展示。
      row.put("publishedAt", v.publishedAt() == null ? null : v.publishedAt().toString());
      row.put("publishedByOperatorId",
          v.publishedByOperatorId() == null ? null : String.valueOf(v.publishedByOperatorId()));
      row.put("current", v.current());
      items.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("total", items.size());
    return envelope(data, req);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> publish(@RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    AdminMerchantAgreementHttpRequests.body body =
        AdminMerchantAgreementHttpRequests.decode(json);
    var session = admin(req, "merchant.application.decide");
    if (body.content() == null || body.content().isBlank())
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "协议内容不能为空");
    MerchantAgreementService.PublishedAgreement published =
        agreements.publishAgreement(body.content(),
            Long.parseLong(session.principal().operatorId()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("versionId", String.valueOf(published.versionId()));
    data.put("agreementVersion", published.agreementVersion());
    data.put("contentSha256", published.contentSha256());
    return envelope(data, req);
  }
}
