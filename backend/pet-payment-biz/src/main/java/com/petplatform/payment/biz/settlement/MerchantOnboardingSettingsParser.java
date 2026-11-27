package com.petplatform.payment.biz.settlement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.FeeRate;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the deployment-provided {@code pet.payment.onboarding.*} values (CCR-PAY-ONBOARD-001 §10) into
 * {@link MerchantOnboardingService.Settings}. Blank values are allowed at startup — they only make
 * "发起开通" fail closed with the missing items — but a present value must be well formed, so a typo in
 * the JSON maps or the ratio is caught at boot instead of at the first submission.
 */
public final class MerchantOnboardingSettingsParser {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern TYPE_CODE = Pattern.compile("[0-9A-Za-z]{1,8}");
    private static final Pattern RATE = Pattern.compile("(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,2})?");
    private static final Pattern RATIO = Pattern.compile("(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,2})?");

    /** Raw property values exactly as bound from the environment. */
    public record Raw(
            String notifyBaseUrl,
            String posType,
            String merBusiContent,
            String settlePeriod,
            String feeRatesJson,
            String mccDefault,
            String mccByMerchantTypeJson,
            String districtByCityJson,
            String lowestRatioPercent,
            String cooperationAgreementName,
            String platformCompanyName) {}

    private MerchantOnboardingSettingsParser() {}

    public static MerchantOnboardingService.Settings parse(Raw raw, String orgCode, String platformReceiverNo) {
        if (raw == null) throw new IllegalArgumentException("pet.payment.onboarding 缺少配置绑定");
        List<FeeRate> fees = new ArrayList<>();
        for (Map<String, String> fee : objects(raw.feeRatesJson(), "fee-rates-json")) {
            String code = required(fee, "feeRateTypeCode", "fee-rates-json");
            if (!TYPE_CODE.matcher(code).matches()) throw new IllegalArgumentException("fee-rates-json 的 feeRateTypeCode 不合法");
            String name = required(fee, "feeRateTypeName", "fee-rates-json");
            if (name.length() > 64) throw new IllegalArgumentException("fee-rates-json 的 feeRateTypeName 过长");
            String pct = required(fee, "feeRatePct", "fee-rates-json");
            if (!RATE.matcher(pct).matches()) throw new IllegalArgumentException("fee-rates-json 的 feeRatePct 不合法");
            fees.add(new FeeRate(code, name, pct));
        }
        Map<String, String> mccByType = strings(raw.mccByMerchantTypeJson(), "mcc-by-merchant-type-json");
        Map<String, String> districts = strings(raw.districtByCityJson(), "district-by-city-json");
        for (String value : mccByType.values()) {
            if (!value.matches("[0-9]{4}")) throw new IllegalArgumentException("mcc-by-merchant-type-json 的 MCC 须为 4 位数字");
        }
        for (String value : districts.values()) {
            if (!value.matches("[0-9]{6,8}")) throw new IllegalArgumentException("district-by-city-json 的地区码须为 6～8 位数字");
        }
        String mccDefault = blank(raw.mccDefault()) ? null : raw.mccDefault().trim();
        if (mccDefault != null && !mccDefault.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("mcc-default 须为 4 位数字");
        }
        BigDecimal ratio = null;
        if (!blank(raw.lowestRatioPercent())) {
            String text = raw.lowestRatioPercent().trim();
            if (!RATIO.matcher(text).matches()) throw new IllegalArgumentException("lowest-ratio-percent 须为 0～100 且最多两位小数");
            ratio = new BigDecimal(text);
            if (ratio.signum() <= 0 || ratio.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("lowest-ratio-percent 须在 (0, 100] 之间");
            }
        }
        String org = blank(orgCode) ? "" : orgCode.trim();
        if (!org.isEmpty() && !org.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("pet.payment.settlement.org-code 须为数字（拉卡拉机构号）");
        }
        return new MerchantOnboardingService.Settings(
                blank(raw.notifyBaseUrl()) ? "" : raw.notifyBaseUrl().trim(),
                org,
                blank(raw.posType()) ? "" : raw.posType().trim(),
                blank(raw.merBusiContent()) ? "" : raw.merBusiContent().trim(),
                blank(raw.settlePeriod()) ? "" : raw.settlePeriod().trim(),
                fees, mccDefault, mccByType, districts, ratio,
                blank(platformReceiverNo) ? "" : platformReceiverNo.trim(),
                blank(raw.cooperationAgreementName()) ? "" : raw.cooperationAgreementName().trim(),
                blank(raw.platformCompanyName()) ? "" : raw.platformCompanyName().trim());
    }

    private static List<Map<String, String>> objects(String json, String key) {
        if (blank(json)) return List.of();
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) throw new IllegalArgumentException(key + " 须为 JSON 数组");
            List<Map<String, String>> out = new ArrayList<>();
            for (JsonNode entry : root) {
                out.add(stringMap(entry, key));
            }
            return out;
        } catch (IOException malformed) {
            throw new IllegalArgumentException(key + " 不是合法 JSON");
        }
    }

    private static Map<String, String> strings(String json, String key) {
        if (blank(json)) return Map.of();
        try {
            return stringMap(JSON.readTree(json), key);
        } catch (IOException malformed) {
            throw new IllegalArgumentException(key + " 不是合法 JSON");
        }
    }

    private static Map<String, String> stringMap(JsonNode node, String key) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(key + " 须为 JSON 对象");
        return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, String>>() {});
    }

    private static String required(Map<String, String> map, String field, String key) {
        String value = map.get(field);
        if (blank(value)) throw new IllegalArgumentException(key + " 缺少 " + field);
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
