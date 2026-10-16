package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpRequests.*;
import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantAdmissionApiImpl;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** GET /api/v1/merchant/auth/admission (CCR-W2-ADMISSION-001 §3): re-checked entry facts. */
@RestController
@RequestMapping(value = "/api/v1/merchant/auth/admission", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public final class MerchantAdmissionController {
  private final MerchantAdmissionApiImpl admission;

  public MerchantAdmissionController(MerchantAdmissionApiImpl admission) {
    this.admission = admission;
  }

  @ModelAttribute
  void noStore(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping
  Map<String, Object> get(@RequestParam String merchantId, @RequestParam String storeId, HttpServletRequest req) {
    onlyParameters(req, "merchantId", "storeId");
    MerchantAdmissionDTO value =
        admission.getAdmission(new MerchantAdmissionQuery(merchantId, storeId, userQuery(req)));
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("application", value.application() == null ? null
        : Map.of("status", value.application().status()));
    facts.put("signing", Map.of("status", value.signing().status()));
    facts.put("storeStatus", value.storeStatus());
    facts.put("merchantStatus", value.merchantStatus());
    facts.put("staffEnabled", value.staffEnabled());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("merchantId", value.merchantId());
    data.put("storeId", value.storeId());
    data.put("membershipKind", value.membershipKind());
    data.put("admission", value.admission());
    data.put("checkedAt", time(value.checkedAt()));
    data.put("authzVersion", value.authzVersion());
    data.put("facts", facts);
    data.put("allowedActions", value.allowedActions());
    data.put("reasonCodes", value.reasonCodes());
    data.put("nextSteps", value.nextSteps().stream().map(step -> Map.of("type", step.type())).toList());
    return envelope(data, req);
  }
}
