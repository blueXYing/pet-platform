package com.petplatform.boot.config;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pet.merchant.application")
public class MerchantApplicationCityProperties {
  private List<Entry> openCities = List.of();

  public List<Entry> getOpenCities() {
    return openCities;
  }

  public void setOpenCities(List<Entry> openCities) {
    this.openCities = openCities == null ? List.of() : List.copyOf(openCities);
  }

  public static class Entry {
    private String code;
    private String name;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
