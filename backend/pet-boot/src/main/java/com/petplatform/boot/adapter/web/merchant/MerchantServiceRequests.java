package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.invalid;

/** Strict JSON field readers shared by the service write controllers. */
final class MerchantServiceRequests {
    private MerchantServiceRequests() {}

    private static final tools.jackson.databind.ObjectReader STRICT =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();

    static String text(String json, String field) {
        tools.jackson.databind.JsonNode node = root(json).get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) throw invalid();
        return node.asString();
    }

    static String optionalText(String json, String field, int maxLength) {
        tools.jackson.databind.JsonNode node = root(json).get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString() || node.asString().isBlank()
                || node.asString().length() > maxLength) throw invalid();
        return node.asString();
    }

    private static tools.jackson.databind.JsonNode root(String json) {
        try {
            tools.jackson.databind.JsonNode root = STRICT.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }
}
