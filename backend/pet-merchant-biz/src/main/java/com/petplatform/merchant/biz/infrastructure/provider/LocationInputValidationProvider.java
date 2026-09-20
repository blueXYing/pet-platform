package com.petplatform.merchant.biz.infrastructure.provider;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import java.math.BigDecimal;

/**
 * Validates only required location input shape. Geographic matching is deliberately out of scope.
 * The independently configured OpenCityReader remains the authority for application city codes.
 */
public final class LocationInputValidationProvider implements MapValidationPort {
  private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
  private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
  private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
  private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");

  @Override
  public boolean isReasonable(
      String cityCode, String address, BigDecimal longitude, BigDecimal latitude) {
    return address != null
        && !address.isBlank()
        && address.codePointCount(0, address.length()) <= 255
        && longitude != null
        && longitude.compareTo(MIN_LONGITUDE) >= 0
        && longitude.compareTo(MAX_LONGITUDE) <= 0
        && latitude != null
        && latitude.compareTo(MIN_LATITUDE) >= 0
        && latitude.compareTo(MAX_LATITUDE) <= 0;
  }
}
