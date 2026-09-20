package com.petplatform.boot.config;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Endpoint-local immutable JSON reader; leaves the existing AUTH serializer unchanged. */
public final class MerchantJsonReaderFactory {
  private MerchantJsonReaderFactory() {}

  public static ObjectReader strictReader() {
    return JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build().reader();
  }
}
