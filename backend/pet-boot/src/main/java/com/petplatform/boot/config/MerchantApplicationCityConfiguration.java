package com.petplatform.boot.config;

import com.petplatform.merchant.biz.application.ApplicationValidationPorts.OpenCityReader;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MerchantApplicationCityProperties.class)
public class MerchantApplicationCityConfiguration {
  @Bean
  @ConditionalOnMissingBean(OpenCityReader.class)
  MerchantApplicationCityCatalog merchantApplicationCityCatalog(
      MerchantApplicationCityProperties properties) {
    return new MerchantApplicationCityCatalog(
        properties.getOpenCities().stream()
            .map(entry -> new MerchantApplicationCityCatalog.City(entry.getCode(), entry.getName()))
            .toList());
  }
}
