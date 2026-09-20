package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class PrivateAssetHttpRequests {
  private static final tools.jackson.databind.ObjectReader STRICT =
      com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
  private static final Pattern PURPOSE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  private PrivateAssetHttpRequests() {}

  record IssueGrant(
      String submissionRevisionId, String purposeCode, String reason, Boolean confirmed) {
    IssueGrant {
      id(submissionRevisionId);
      if (purposeCode == null
          || !PURPOSE.matcher(purposeCode).matches()
          || reason == null
          || reason.codePointCount(0, reason.length()) < 10
          || reason.codePointCount(0, reason.length()) > 500
          || !reason.equals(reason.strip())
          || !Boolean.TRUE.equals(confirmed)) throw invalid();
    }
  }

  static IssueGrant issueGrant(String json) {
    try {
      JsonNode root = STRICT.readTree(json);
      if (root == null
          || !root.isObject()
          || root.get("submissionRevisionId") == null
          || !root.get("submissionRevisionId").isTextual()
          || root.get("purposeCode") == null
          || !root.get("purposeCode").isTextual()
          || root.get("reason") == null
          || !root.get("reason").isTextual()
          || root.get("confirmed") == null
          || !root.get("confirmed").isBoolean()) throw invalid();
      return STRICT.forType(IssueGrant.class).readValue(root);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }
}
