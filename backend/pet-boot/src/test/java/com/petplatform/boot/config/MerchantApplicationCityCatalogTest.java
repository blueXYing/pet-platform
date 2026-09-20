package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts.OpenCityReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MerchantApplicationCityCatalogTest {
  @Test
  void configuredCatalogAcceptsOnlyItsOpaqueCode() {
    var catalog =
        new MerchantApplicationCityCatalog(
            List.of(new MerchantApplicationCityCatalog.City("chengdu", "成都")));
    assertTrue(catalog.isOpen("chengdu"));
    assertFalse(catalog.isOpen("成都"));
    assertFalse(catalog.isOpen("510100"));
    assertFalse(catalog.isOpen("CHENGDU"));
    assertEquals("成都", catalog.list().getFirst().name());
  }

  @Test
  void emptyAndInvalidCatalogsFailClosed() {
    var empty = new MerchantApplicationCityCatalog(List.of());
    assertThrows(ApiException.class, () -> empty.isOpen("chengdu"));
    ApiException unavailable = assertThrows(ApiException.class, empty::list);
    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MerchantApplicationCityCatalog(
                List.of(
                    new MerchantApplicationCityCatalog.City("chengdu", "成都"),
                    new MerchantApplicationCityCatalog.City("chengdu", "成都二"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MerchantApplicationCityCatalog.City("510100", "成都"));
  }

  @Test
  void propertiesBindTrustedStartupDirectoryAndFixtureCanOverrideReader() {
    new ApplicationContextRunner()
        .withUserConfiguration(MerchantApplicationCityConfiguration.class)
        .withPropertyValues(
            "pet.merchant.application.enabled=true",
            "pet.merchant.application.open-cities[0].code=chengdu",
            "pet.merchant.application.open-cities[0].name=成都")
        .run(
            ctx -> {
              assertNull(ctx.getStartupFailure());
              assertTrue(ctx.getBean(OpenCityReader.class).isOpen("chengdu"));
              assertEquals(1, ctx.getBean(MerchantApplicationCityCatalog.class).list().size());
            });

    OpenCityReader fixture = code -> "fixture".equals(code);
    new ApplicationContextRunner()
        .withUserConfiguration(MerchantApplicationCityConfiguration.class)
        .withBean(OpenCityReader.class, () -> fixture)
        .withPropertyValues("pet.merchant.application.enabled=true")
        .run(
            ctx -> {
              assertSame(fixture, ctx.getBean(OpenCityReader.class));
              assertTrue(ctx.getBeansOfType(MerchantApplicationCityCatalog.class).isEmpty());
            });
  }
}
