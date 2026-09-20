package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts.OpenCityReader;
import java.util.*;
import java.util.regex.Pattern;

/** Trusted, startup-bound directory of cities currently open for merchant applications. */
public final class MerchantApplicationCityCatalog implements OpenCityReader {
  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
  private final List<City> cities;
  private final Set<String> codes;

  public MerchantApplicationCityCatalog(List<City> cities) {
    List<City> copy = List.copyOf(Objects.requireNonNull(cities));
    if (copy.size() > 100
        || copy.stream().anyMatch(Objects::isNull)
        || copy.stream().map(City::code).distinct().count() != copy.size()
        || copy.stream().map(City::name).distinct().count() != copy.size()) {
      throw new IllegalArgumentException("Open-city entries must be unique");
    }
    this.cities = copy;
    this.codes = Set.copyOf(copy.stream().map(City::code).toList());
  }

  @Override
  public boolean isOpen(String cityCode) {
    requireConfigured();
    return cityCode != null && codes.contains(cityCode);
  }

  /** Empty configuration is an unavailable trusted directory, never an implicit open city. */
  public List<City> list() {
    requireConfigured();
    return cities;
  }

  private void requireConfigured() {
    if (cities.isEmpty())
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录暂时不可用");
  }

  public record City(String code, String name) {
    public City {
      if (code == null
          || !CODE.matcher(code).matches()
          || name == null
          || name.isBlank()
          || name.length() > 64) {
        throw new IllegalArgumentException("Invalid open-city entry");
      }
    }
  }
}
