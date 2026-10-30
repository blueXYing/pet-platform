package com.petplatform.marketing.biz.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.petplatform.common.ApiException;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignRow;
import java.util.Set;

/**
 * Contract 71 §2.5 schema validation and default template. Only the backend-driven component
 * whitelist is accepted; unknown component types are rejected at publish (the C renderer still
 * skips them defensively when reading an older/foreign schema).
 */
public final class MarketingPageSchema {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPES = Set.of(
            "hero", "coupon", "storeList", "richText", "notice",
            "buttonGroup", "spacer", "divider");
    private static final Set<String> ACTIONS = Set.of(
            "STORE", "SERVICE", "CAMPAIGN", "SERVICE_CATEGORY", "MINIAPP_PATH",
            "COUPON_CLAIM", "NONE");

    private MarketingPageSchema() {}

    public static String validate(String schemaJson) {
        return validate(schemaJson, false);
    }

    public static String validate(String schemaJson, boolean allowHttpAssets) {
        if (schemaJson == null || schemaJson.isBlank()) invalid("page schema is required");
        JsonNode root;
        try {
            root = JSON.readTree(schemaJson);
        } catch (Exception failure) {
            invalid("page schema is not valid JSON");
            return null;
        }
        if (root == null || !root.isObject()) invalid("page schema must be an object");
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isInt() || version.asInt() != 1) {
            invalid("schemaVersion must be 1");
        }
        JsonNode components = root.get("components");
        if (components == null || !components.isArray() || components.isEmpty()
                || components.size() > 10) {
            invalid("components must contain 1..10 items");
        }
        for (JsonNode component : components) {
            if (!component.isObject()) invalid("component must be an object");
            JsonNode type = component.get("type");
            if (type == null || !type.isTextual() || !TYPES.contains(type.asText())) {
                invalid("unknown component type");
            }
            validateComponent(type.asText(), component, allowHttpAssets);
        }
        return root.toString();
    }

    private static void validateComponent(String type, JsonNode c, boolean allowHttpAssets) {
        switch (type) {
            case "hero" -> {
                requiredText(c, "title", 1, 100);
                optionalText(c, "subtitle", 200);
                optionalText(c, "tag", 32);
                optionalUrl(c, "imageUrl", allowHttpAssets);
            }
            case "coupon" -> optionalText(c, "buttonText", 20);
            case "storeList" -> optionalText(c, "title", 100);
            case "richText" -> {
                optionalText(c, "title", 100);
                JsonNode paragraphs = c.get("paragraphs");
                if (paragraphs == null || !paragraphs.isArray() || paragraphs.isEmpty()
                        || paragraphs.size() > 10) {
                    invalid("richText.paragraphs must contain 1..10 items");
                }
                for (JsonNode paragraph : paragraphs) {
                    if (!paragraph.isTextual()
                            || paragraph.asText().codePointCount(0, paragraph.asText().length()) > 500) {
                        invalid("richText paragraph is too long");
                    }
                }
            }
            case "notice" -> requiredText(c, "text", 1, 200);
            case "buttonGroup" -> {
                JsonNode buttons = c.get("buttons");
                if (buttons == null || !buttons.isArray() || buttons.isEmpty()
                        || buttons.size() > 3) {
                    invalid("buttonGroup.buttons must contain 1..3 items");
                }
                for (JsonNode button : buttons) {
                    if (!button.isObject()) invalid("button must be an object");
                    requiredText(button, "label", 1, 20);
                    JsonNode action = button.get("action");
                    if (action == null || !action.isObject()) invalid("button.action is required");
                    JsonNode actionType = action.get("type");
                    if (actionType == null || !actionType.isTextual()
                            || !ACTIONS.contains(actionType.asText())) {
                        invalid("unknown button action type");
                    }
                    switch (actionType.asText()) {
                        case "STORE", "SERVICE", "CAMPAIGN", "SERVICE_CATEGORY" ->
                                requiredId(action, "targetId");
                        case "MINIAPP_PATH" -> {
                            requiredText(action, "path", 1, 256);
                            String path = action.get("path").asText();
                            if (!(path.startsWith("/consumer/pages/") || path.startsWith("/merchant/pages/"))
                                    || path.contains("..")) {
                                invalid("miniapp path is not whitelisted");
                            }
                        }
                        default -> { }
                    }
                }
            }
            case "spacer" -> {
                JsonNode height = c.get("height");
                if (height == null || !height.isInt() || height.asInt() < 8 || height.asInt() > 80) {
                    invalid("spacer.height must be 8..80");
                }
            }
            case "divider" -> { }
            default -> invalid("unknown component type");
        }
    }

    private static void requiredText(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) invalid(field + " is required");
        String text = value.asText().strip();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) invalid(field + " length is invalid");
    }

    private static void optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || value.asText().codePointCount(0, value.asText().length()) > max) {
            invalid(field + " length is invalid");
        }
    }

    private static void optionalUrl(JsonNode node, String field, boolean allowHttpAssets) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return;
        if (!value.isTextual()) invalid(field + " is invalid");
        String url = value.asText();
        boolean secure = url.startsWith("https://");
        boolean localHttp = allowHttpAssets && url.startsWith("http://");
        if ((!secure && !localHttp) || url.length() > 512) invalid(field + " is invalid");
    }

    private static void requiredId(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()
                || !value.asText().matches("[1-9][0-9]{0,18}")) {
            invalid(field + " is required");
        }
    }

    /** Server default for campaigns created without a page editor payload. */
    public static String defaultSchema(CampaignRow row) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        ArrayNode components = root.putArray("components");
        ObjectNode hero = components.addObject();
        hero.put("type", "hero");
        hero.put("title", row.title == null ? row.name : row.title);
        if (row.subtitle != null) hero.put("subtitle", row.subtitle);
        if (row.coverUrl != null) hero.put("imageUrl", row.coverUrl);
        if (row.tagName != null) hero.put("tag", row.tagName);
        if (row.couponCampaignId != null) {
            ObjectNode coupon = components.addObject();
            coupon.put("type", "coupon");
            coupon.put("buttonText", "立即领取");
        }
        ObjectNode stores = components.addObject();
        stores.put("type", "storeList");
        stores.put("title", "参与活动商家");
        ObjectNode rich = components.addObject();
        rich.put("type", "richText");
        rich.put("title", "活动说明");
        rich.putArray("paragraphs").add(
                "活动内容与优惠以页面展示及门店实际服务为准；如有疑问请联系平台客服。");
        return root.toString();
    }

    public static String publishedOrDefault(CampaignRow row) {
        if (row.publishedPageSchemaJson != null && !row.publishedPageSchemaJson.isBlank()) {
            return row.publishedPageSchemaJson;
        }
        if (row.pageSchemaJson != null && !row.pageSchemaJson.isBlank()) {
            return row.pageSchemaJson;
        }
        return defaultSchema(row);
    }

    private static void invalid(String message) {
        throw new ApiException("CAMPAIGN_PAGE_INVALID", message);
    }
}
