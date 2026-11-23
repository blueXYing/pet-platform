package com.petplatform.boot.adapter.web.merchant;

import tools.jackson.databind.JsonNode;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;

/** Strict body decoding for the admin agreement publish endpoint (unknown fields rejected). */
final class AdminMerchantAgreementHttpRequests {
  record body(String content) {}

  private static final tools.jackson.databind.ObjectReader STRICT = com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();

  static body decode(String json) {
    JsonNode root;
    try {
      root = STRICT.readTree(json);
    } catch (RuntimeException bad) {
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
    if (root == null || !root.isObject() || root.size() != 1 || !root.has("content")
        || !root.path("content").isTextual()) {
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
    return new body(root.path("content").asText());
  }

  private AdminMerchantAgreementHttpRequests() {}
}
