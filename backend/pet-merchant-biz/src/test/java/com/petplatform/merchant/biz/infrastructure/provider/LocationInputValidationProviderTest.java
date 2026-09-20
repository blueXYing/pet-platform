package com.petplatform.merchant.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LocationInputValidationProviderTest {
  private final LocationInputValidationProvider validator = new LocationInputValidationProvider();

  @Test
  void acceptsRequiredAddressAndAnyEarthLegalCoordinatesWithoutGeographicMatching() {
    assertTrue(
        validator.isReasonable(
            "chengdu", "商家选择的地址", new BigDecimal("116.397128"), new BigDecimal("39.916527")),
        "Beijing coordinates remain valid input for a Chengdu application after location limits were cancelled");
    assertTrue(
        validator.isReasonable(
            "chengdu", "另一个地址", new BigDecimal("121.473701"), new BigDecimal("31.230416")),
        "the validator must not infer or enforce a city from coordinates");
    assertTrue(
        validator.isReasonable(
            "chengdu", "边界点", new BigDecimal("-180"), new BigDecimal("-90")));
    assertTrue(
        validator.isReasonable(
            "chengdu", "边界点", new BigDecimal("180"), new BigDecimal("90")));
  }

  @Test
  void rejectsMissingOrOversizedAddressAndCoordinatesOutsideEarthRanges() {
    assertFalse(validator.isReasonable("chengdu", null, BigDecimal.ZERO, BigDecimal.ZERO));
    assertFalse(validator.isReasonable("chengdu", "   ", BigDecimal.ZERO, BigDecimal.ZERO));
    assertFalse(validator.isReasonable("chengdu", "地".repeat(256), BigDecimal.ZERO, BigDecimal.ZERO));
    assertTrue(
        validator.isReasonable(
            "chengdu", "🐶".repeat(255), BigDecimal.ZERO, BigDecimal.ZERO),
        "the address contract counts Unicode code points rather than UTF-16 code units");
    assertFalse(
        validator.isReasonable(
            "chengdu", "🐶".repeat(256), BigDecimal.ZERO, BigDecimal.ZERO));
    assertFalse(validator.isReasonable("chengdu", "地址", null, BigDecimal.ZERO));
    assertFalse(validator.isReasonable("chengdu", "地址", BigDecimal.ZERO, null));
    assertFalse(
        validator.isReasonable(
            "chengdu", "地址", new BigDecimal("180.0000001"), BigDecimal.ZERO));
    assertFalse(
        validator.isReasonable(
            "chengdu", "地址", new BigDecimal("-180.0000001"), BigDecimal.ZERO));
    assertFalse(
        validator.isReasonable(
            "chengdu", "地址", BigDecimal.ZERO, new BigDecimal("90.0000001")));
    assertFalse(
        validator.isReasonable(
            "chengdu", "地址", BigDecimal.ZERO, new BigDecimal("-90.0000001")));
  }
}
