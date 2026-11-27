package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileInput;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Contract 81 §4.4 validation rules as the service applies them (Lakala 1020/1208 limits, SSOT §二十七 IDs). */
class MerchantOnboardingValidatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final String LEGAL_ID = "342523199905298212";
    private static final String SETTLER_ID = "110101199003074258";

    @Test
    void validProfileIsNormalized() {
        MerchantOnboardingValidator.Profile profile = MerchantOnboardingValidator.validate(command(
                merchant("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院", "91110108ma2g7lk368",
                        "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                        "李四", "13900000000"),
                settlement("58", "张三", "6212260200102400000", null, null, null,
                        "102100000030", "中国工商银行北京市分行营业部", "102100099996"),
                requiredAttachments(), "201", "线下提供"), TODAY);
        assertEquals("91110108MA2G7LK368", profile.merchant().merBlisNo());
        assertNull(profile.merchant().mccCode());
        assertNull(profile.merchant().merBizName());
        assertEquals(MerchantOnboardingValidator.RESIDENT_ID_TYPE, "01");
        assertEquals(6, profile.attachments().size());
        assertNull(profile.settlement().acctIdType());
        assertNull(profile.settlement().acctIdcard());
    }

    @Test
    void rejectsNamesAddressesAndLicenceThatBreakLakalaLimits() {
        assertInvalid(merchant("宠物", null, "510104", "天府大道1号", null, "宠物", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "商户注册名称");
        assertInvalid(merchant("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院有限公司",
                "91110108MA2G7LK368", "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29",
                "13800001234", "李四", "13900000000"), "一致");
        assertInvalid(merchant("示例宠物医院", null, "510104", "府南1号", null, "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "6～80");
        assertInvalid(merchant("示例宠物医院", null, "5101", "天府大道1号", null, "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "地区码");
        assertInvalid(merchant("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院", "9111-0108-MA2G",
                "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "营业执照号");
        assertInvalid(merchant("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2026-11-26", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "已过期");
        assertInvalid(merchant("示例宠物医院", "新店", "510104", "天府大道1号", null, "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "商户经营名称");
        assertInvalid(merchant("示例宠物医院", null, "510104", "天府大道1号", "59", "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", LEGAL_ID, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000"), "MCC");
    }

    @Test
    void residentIdNeedsGb11643CheckDigitAndPlausibleBirthDate() {
        assertInvalid(baseMerchant("342523199905298213"), "校验位");
        assertInvalid(baseMerchant("3425231999052982"), "格式");
        assertInvalid(baseMerchant("110101189901074258"), "出生日期");
        assertInvalid(baseMerchant("110101209912310001"), "出生日期");
        assertEquals("11010119900307424X", MerchantOnboardingValidator.residentId("11010119900307424x", "身份证"));
        assertEquals("130503670401001", MerchantOnboardingValidator.residentId("130503670401001", "身份证"));
        assertInvalid(baseMerchant(LEGAL_ID, "13800001234", "1999-05-29", "2026-11-26"), "法人身份证已过期");
    }

    @Test
    void settlementAccountFollowsAccountTypeAndSettlerRules() {
        // 对公: the account name must equal the registered name.
        assertInvalid(validMerchant(), settlement("57", "张三", "6212260200102400000", null, null, null,
                "102100000030", "中国工商银行北京市分行营业部", "102100099996"), "对公结算账户名称");
        MerchantOnboardingValidator.Profile corporate = MerchantOnboardingValidator.validate(command(
                validMerchant(), settlement("57", "示例宠物医院", "1100220000000000001", null, null, null,
                        "102100000030", "中国工商银行北京市分行营业部", "102100099996"),
                requiredAttachments(), "201", "线下提供"), TODAY);
        assertEquals("57", corporate.settlement().acctTypeCode());
        // 对私 with a different settler: all three settler fields and the three extra attachments are required.
        assertInvalid(validMerchant(), settlement("58", "张三", "6212260200102400000", "01", SETTLER_ID,
                "2040-01-01", "102100000030", "中国工商银行北京市分行营业部", "102100099996"), "非法人结算缺少附件");
        assertInvalid(validMerchant(), settlement("58", "张三", "6212260200102400000", "01", SETTLER_ID,
                null, "102100000030", "中国工商银行北京市分行营业部", "102100099996"), "同时填写或同时留空");
        assertInvalid(validMerchant(), settlement("58", "张三", "6212260200102400000", "02", SETTLER_ID,
                "2040-01-01", "102100000030", "中国工商银行北京市分行营业部", "102100099996"), "仅支持身份证");
        assertInvalid(validMerchant(), settlement("58", "张三", "6212260200102400000", "01", LEGAL_ID,
                "2040-01-01", "102100000030", "中国工商银行北京市分行营业部", "102100099996"), "结算人证件号与法人相同");
        Map<String, String> all = new LinkedHashMap<>(requiredAttachments());
        all.put("ID_CARD_FRONT", "301");
        all.put("ID_CARD_BEHIND", "302");
        all.put("NO_LEGAL_PERSON_SETT_AUTH_LETTER", "303");
        MerchantOnboardingValidator.Profile settler = MerchantOnboardingValidator.validate(command(
                validMerchant(), settlement("58", "张三", "6212260200102400000", "01", SETTLER_ID, "2040-01-01",
                        "102100000030", "中国工商银行北京市分行营业部", "102100099996"),
                all, "201", "线下提供"), TODAY);
        assertEquals(9, settler.attachments().size());
        assertEquals(SETTLER_ID, settler.settlement().acctIdcard());
        assertInvalid(validMerchant(), settlement("58", "张三", "621", null, null, null,
                "102100000030", "中国工商银行北京市分行营业部", "102100099996"), "8～40");
        assertInvalid(validMerchant(), settlement("58", "张三", "6212260200102400000", null, null, null,
                "1021", "中国工商银行北京市分行营业部", "102100099996"), "开户行号");
    }

    @Test
    void attachmentsAndReasonAreChecked() {
        Map<String, String> missing = new LinkedHashMap<>(requiredAttachments());
        missing.remove("SHOPINNER");
        assertInvalid(validMerchant(), validSettlement(), missing, "201", "线下提供", "缺少必传附件");
        Map<String, String> settlerOnly = new LinkedHashMap<>(requiredAttachments());
        settlerOnly.put("ID_CARD_FRONT", "301");
        assertInvalid(validMerchant(), validSettlement(), settlerOnly, "201", "线下提供", "必须留空");
        Map<String, String> unknown = new LinkedHashMap<>(requiredAttachments());
        unknown.put("OTHERS", "301");
        assertInvalid(validMerchant(), validSettlement(), unknown, "201", "线下提供", "不支持的附件类型");
        assertInvalid(validMerchant(), validSettlement(), requiredAttachments(), "201", "略", "操作原因");
        assertInvalid(validMerchant(), validSettlement(), requiredAttachments(), "abc", "线下提供", "合作协议素材编号");
        assertNull(MerchantOnboardingValidator.validate(command(validMerchant(), validSettlement(),
                requiredAttachments(), "", "线下提供"), TODAY).cooperationAssetId());
    }

    private static void assertInvalid(MerchantProfileInput merchant, String fragment) {
        assertInvalid(merchant, validSettlement(), requiredAttachments(), "201", "线下提供", fragment);
    }

    private static void assertInvalid(SettlementProfileInput settlement, String fragment) {
        assertInvalid(validMerchant(), settlement, requiredAttachments(), "201", "线下提供", fragment);
    }

    private static void assertInvalid(MerchantProfileInput merchant, SettlementProfileInput settlement,
            String fragment) {
        assertInvalid(merchant, settlement, requiredAttachments(), "201", "线下提供", fragment);
    }

    private static void assertInvalid(Map<String, String> attachments, String cooperation, String reason,
            String fragment) {
        assertInvalid(validMerchant(), validSettlement(), attachments, cooperation, reason, fragment);
    }

    private static MerchantProfileInput baseMerchant(String larIdcard) {
        return merchant("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", larIdcard, "1999-05-29", "2039-05-29", "13800001234",
                "李四", "13900000000");
    }

    private static MerchantProfileInput baseMerchant(String larIdcard, String larMobile, String from, String to) {
        return merchant("示例宠物医院", null, "510104", "天府大道1号", null, "示例宠物医院", "91110108MA2G7LK368",
                "2020-01-01", "2040-01-01", "张三", larIdcard, from, to, larMobile, "李四", "13900000000");
    }

    private static void assertInvalid(MerchantProfileInput merchant, SettlementProfileInput settlement,
            Map<String, String> attachments, String cooperation, String reason, String fragment) {
        ApiException error = assertThrows(ApiException.class, () -> MerchantOnboardingValidator.validate(
                command(merchant, settlement, attachments, cooperation, reason), TODAY));
        assertTrue(error.getMessage().contains(fragment),
                () -> "expected [" + fragment + "] in: " + error.getMessage());
    }

    private static SaveOnboardingCommand command(MerchantProfileInput merchant, SettlementProfileInput settlement,
            Map<String, String> attachments, String cooperation, String reason) {
        return new SaveOnboardingCommand(
                new CommandContext("0b9f7fd4-6b3e-4f7d-9c1a-2d5e8f3a7b11", null, OperatorType.PLATFORM_OPERATOR,
                        "7", "ADMIN_WEB"),
                "9001", 0, merchant, settlement, attachments, cooperation, reason);
    }

    private static MerchantProfileInput validMerchant() {
        return baseMerchant(LEGAL_ID);
    }

    private static SettlementProfileInput validSettlement() {
        return settlement("58", "张三", "6212260200102400000", null, null, null,
                "102100000030", "中国工商银行北京市分行营业部", "102100099996");
    }

    private static Map<String, String> requiredAttachments() {
        Map<String, String> attachments = new LinkedHashMap<>();
        attachments.put("FR_ID_CARD_FRONT", "101");
        attachments.put("FR_ID_CARD_BEHIND", "102");
        attachments.put("BANK_CARD", "103");
        attachments.put("BUSINESS_LICENCE", "104");
        attachments.put("MERCHANT_PHOTO", "105");
        attachments.put("SHOPINNER", "106");
        return attachments;
    }

    private static MerchantProfileInput merchant(String merRegName, String merBizName, String district,
            String address, String mcc, String blisName, String blisNo, String blisFrom, String blisTo,
            String larName, String larIdcard, String larFrom, String larTo, String larMobile,
            String contactName, String contactMobile) {
        return new MerchantProfileInput(merRegName, merBizName, district, address, mcc, blisName, blisNo,
                blisFrom, blisTo, larName, larIdcard, larFrom, larTo, larMobile, contactName, contactMobile);
    }

    private static SettlementProfileInput settlement(String type, String name, String no, String idType,
            String idcard, String idExp, String bankCode, String bankName, String clearing) {
        return new SettlementProfileInput(type, name, no, idType, idcard, idExp, bankCode, bankName, clearing);
    }
}
