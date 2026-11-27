package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.FeeRate;
import com.petplatform.payment.biz.settlement.MerchantOnboardingService.Settings;
import com.petplatform.payment.biz.settlement.MerchantOnboardingSettingsParser.Raw;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Start-up parsing of pet.payment.onboarding.* (CCR-PAY-ONBOARD-001 §10): blanks pass, typos fail. */
class MerchantOnboardingSettingsParserTest {
    private static final Raw BLANK = new Raw("", "", "", "", "", "", "", "", "", "", "", "");

    @Test
    void blankValuesAreAllowedAndReadAsUnset() {
        Settings settings = MerchantOnboardingSettingsParser.parse(BLANK, null, null);
        assertEquals("", settings.notifyBaseUrl());
        assertEquals("", settings.orgCode());
        assertNull(settings.lowestRatio());
        assertEquals("", settings.platformReceiverNo());
        assertEquals("", settings.ecOrgId());
        assertTrue(settings.feeRates().isEmpty());
        assertTrue(settings.mccByMerchantType().isEmpty());
        assertTrue(settings.districtByCity().isEmpty());
    }

    @Test
    void populatedValuesAreParsedAndTrimmed() {
        Settings settings = MerchantOnboardingSettingsParser.parse(new Raw(
                " https://pet.example/api/v1/callbacks/lakala/onboard ", " CLOUD_DISTRIBUTION ", "654", "T+1",
                "[{\"feeRateTypeCode\":\"302\",\"feeRateTypeName\":\"微信\",\"feeRatePct\":\"0.60\"},"
                        + "{\"feeRateTypeCode\":\"300\",\"feeRateTypeName\":\"银行卡借记卡\",\"feeRatePct\":\"0.38\"}]",
                "5995", "{\"PET_HOSPITAL\":\"0742\",\"PET_GROOMING\":\"7230\"}", "{\"chengdu\":\"510100\"}",
                "90.50", "宠物平台商家合作协议", "示例科技有限公司", " 1950836 "), " 1950836 ", " SR20261126PLAT01 ");
        assertEquals("https://pet.example/api/v1/callbacks/lakala/onboard", settings.notifyBaseUrl());
        assertEquals("CLOUD_DISTRIBUTION", settings.posType());
        assertEquals(2, settings.feeRates().size());
        assertEquals("0742", settings.mccByMerchantType().get("PET_HOSPITAL"));
        assertEquals("510100", settings.districtByCity().get("chengdu"));
        assertEquals(new BigDecimal("90.50"), settings.lowestRatio());
        assertEquals("1950836", settings.orgCode());
        assertEquals("1950836", settings.ecOrgId());
        assertEquals("SR20261126PLAT01", settings.platformReceiverNo());
    }

    @Test
    void theSharedOrganizationCodeIsNeverValidatedHere() {
        // 进件/分账 take the org code as a free-form string; whether an integer org id exists for the
        // electronic contract is decided per onboarding attempt.
        Settings freeForm = MerchantOnboardingSettingsParser.parse(BLANK, " org-test ", null);
        assertEquals("org-test", freeForm.orgCode());
        assertEquals("", freeForm.ecOrgId());
    }

    @Test
    void malformedValuesAreRejectedWithTheOffendingKey() {
        assertRejected(raw("feeRatesJson", "not-json"), "fee-rates-json");
        assertRejected(raw("feeRatesJson", "{\"feeRateTypeCode\":\"302\"}"), "fee-rates-json");
        assertRejected(raw("feeRatesJson", "[{\"feeRateTypeCode\":\"302\",\"feeRateTypeName\":\"微信\",\"feeRatePct\":\"0.6.0\"}]"),
                "fee-rates-json");
        assertRejected(raw("feeRatesJson", "[{\"feeRateTypeCode\":\"302\",\"feeRatePct\":\"0.60\"}]"), "feeRateTypeName");
        assertRejected(raw("mccByMerchantTypeJson", "{\"PET_HOSPITAL\":\"74\"}"), "mcc-by-merchant-type-json");
        assertRejected(raw("mccByMerchantTypeJson", "[\"0742\"]"), "mcc-by-merchant-type-json");
        assertRejected(raw("districtByCityJson", "{\"chengdu\":\"51\"}"), "district-by-city-json");
        assertRejected(raw("mccDefault", "74"), "mcc-default");
        assertRejected(raw("lowestRatioPercent", "0"), "lowest-ratio-percent");
        assertRejected(raw("lowestRatioPercent", "100.01"), "lowest-ratio-percent");
        assertRejected(raw("lowestRatioPercent", "90.505"), "lowest-ratio-percent");
        assertRejected(raw("ecOrgId", "org-1"), "ec-org-id");
    }

    private static Raw raw(String field, String value) {
        String[] values = {"", "", "", "", "", "", "", "", "", "", "", ""};
        int index = switch (field) {
            case "feeRatesJson" -> 4;
            case "mccDefault" -> 5;
            case "mccByMerchantTypeJson" -> 6;
            case "districtByCityJson" -> 7;
            case "lowestRatioPercent" -> 8;
            case "ecOrgId" -> 11;
            default -> throw new AssertionError(field);
        };
        values[index] = value;
        return new Raw(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
                values[8], values[9], values[10], values[11]);
    }

    private static void assertRejected(Raw raw, String fragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MerchantOnboardingSettingsParser.parse(raw, null, null));
        assertTrue(error.getMessage().contains(fragment), () -> "expected [" + fragment + "] in: " + error.getMessage());
    }
}
