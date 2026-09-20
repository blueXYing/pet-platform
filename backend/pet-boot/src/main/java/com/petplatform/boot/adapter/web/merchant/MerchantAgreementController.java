package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpRequests.*;
import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.command.MerchantAgreementConsentCommand;
import com.petplatform.merchant.api.dto.*;
import com.petplatform.merchant.api.query.*;
import com.petplatform.merchant.biz.apiimpl.MerchantAgreementApiImpl;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/merchant/agreement", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public final class MerchantAgreementController {
  private final MerchantAgreementApiImpl agreements;

  public MerchantAgreementController(MerchantAgreementApiImpl agreements) {
    this.agreements = agreements;
  }

  @ModelAttribute
  void noStore(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping
  Map<String, Object> get(@RequestParam String merchantId, HttpServletRequest req) {
    onlyParameters(req, "merchantId");
    MerchantAgreementDTO value =
        agreements.getAgreement(new MerchantAgreementQuery(id(merchantId), userQuery(req)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("merchantId", value.merchantId());
    data.put("agreementVersion", value.agreementVersion());
    data.put("content", value.content());
    data.put("contentSha256", value.contentSha256());
    data.put("signingStatus", value.signingStatus());
    if (value.acceptedVersion() != null) data.put("acceptedVersion", value.acceptedVersion());
    if (value.acceptedAt() != null) data.put("acceptedAt", time(value.acceptedAt()));
    return envelope(data, req);
  }

  @PostMapping(value = "/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> consent(@RequestBody String json, HttpServletRequest req, HttpServletResponse response) {
    onlyParameters(req);
    AgreementConsent body = decode(json, AgreementConsent.class);
    MerchantAgreementApiImpl.ConsentOutcome outcome =
        agreements.consentOutcome(
            new MerchantAgreementConsentCommand(
                body.merchantId(),
                body.agreementVersion(),
                body.contentSha256(),
                body.accepted(),
                userCommand(req)));
    MerchantAgreementConsentDTO value = outcome.receipt();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("merchantId", value.merchantId());
    data.put("agreementVersion", value.agreementVersion());
    data.put("acceptedAt", time(value.acceptedAt()));
    data.put("signingStatus", value.signingStatus());
    response.setStatus(outcome.created() ? 201 : 200);
    return envelope(data, req);
  }
}
