package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.payment.biz.settlement.MerchantOnboardingService.Settings;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Offline checks of the onboarding startup guard and its settings mapping (CCR-PAY-ONBOARD-001 §10). */
class PaymentOnboardingConfigurationTest {

    @Test
    void blankSettingsAreAllowedButMalformedOnesStopStartupOnlyWhenPaymentIsEnabled() {
        PaymentOnboardingConfiguration.OnboardingProperties blank = properties(null);
        assertDoesNotThrow(() -> PaymentOnboardingConfiguration.check(true, blank));

        PaymentOnboardingConfiguration.OnboardingProperties broken =
                properties("[{\"feeRateTypeCode\":\"302\"");
        assertThrows(IllegalStateException.class,
                () -> PaymentOnboardingConfiguration.check(true, broken));
        assertDoesNotThrow(() -> PaymentOnboardingConfiguration.check(false, broken));

        PaymentOnboardingConfiguration.OnboardingProperties wrongRatio = properties(null);
        assertThat(assertThrows(IllegalStateException.class, () -> PaymentOnboardingConfiguration.check(true,
                new PaymentOnboardingConfiguration.OnboardingProperties("", "", "", "", "", "", "", "", "0", "", "")))
                .getMessage()).contains("lowest-ratio-percent");
        assertThat(wrongRatio).isNotNull();
    }

    @Test
    void settingsCarryTheSharedOrganizationCodeAndPlatformReceiver() {
        PaymentOnboardingConfiguration.OnboardingProperties properties = new PaymentOnboardingConfiguration.OnboardingProperties(
                "https://pet.example/api/v1/callbacks/lakala/onboard", "CLOUD_DISTRIBUTION", "654", "T+1",
                "[{\"feeRateTypeCode\":\"302\",\"feeRateTypeName\":\"微信\",\"feeRatePct\":\"0.60\"}]",
                "5995", "{\"PET_HOSPITAL\":\"0742\"}", "{\"chengdu\":\"510100\"}", "90.50",
                "宠物平台商家合作协议", "示例科技有限公司");
        Settings settings = PaymentOnboardingConfiguration.settings(properties,
                new PaymentSettlementConfiguration.SettlementSettings("0", " 1950836 ", "https://x/sep", false, "",
                        " SR20261126PLAT01 "));
        assertThat(settings.notifyBaseUrl()).isEqualTo("https://pet.example/api/v1/callbacks/lakala/onboard");
        assertThat(settings.orgCode()).isEqualTo("1950836");
        assertThat(settings.platformReceiverNo()).isEqualTo("SR20261126PLAT01");
        assertThat(settings.lowestRatio()).isEqualByComparingTo(new BigDecimal("90.50"));
        assertThat(settings.feeRates()).hasSize(1);
        assertThat(settings.mccByMerchantType()).containsEntry("PET_HOSPITAL", "0742");
        assertThat(settings.districtByCity()).containsEntry("chengdu", "510100");
        // The 进件 callback URL (base only) must fit the 64-character limit of 1020 retUrl.
        assertThat(settings.notifyBaseUrl().length()).isLessThanOrEqualTo(64);
    }

    private static PaymentOnboardingConfiguration.OnboardingProperties properties(String feeRatesJson) {
        return new PaymentOnboardingConfiguration.OnboardingProperties("", "", "", "", feeRatesJson, "", "", "", "",
                "", "");
    }
}
