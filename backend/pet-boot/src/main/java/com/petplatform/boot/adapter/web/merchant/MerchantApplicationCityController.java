package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = "/api/v1/c/merchant-application-cities",
    produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public final class MerchantApplicationCityController {
  private final ObjectProvider<MerchantApplicationCityCatalog> catalogs;

  public MerchantApplicationCityController(
      ObjectProvider<MerchantApplicationCityCatalog> catalogs) {
    this.catalogs = catalogs;
  }

  @ModelAttribute
  void noStore(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping
  Map<String, Object> list(HttpServletRequest req) {
    onlyParameters(req);
    userQuery(req); // Requires the authoritative current MINIAPP session.
    MerchantApplicationCityCatalog catalog = catalogs.getIfAvailable();
    if (catalog == null) {
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录暂时不可用");
    }
    List<Map<String, String>> items =
        catalog.list().stream()
            .map(city -> Map.of("cityCode", city.code(), "cityName", city.name()))
            .toList();
    return envelope(Map.of("items", items), req);
  }
}
