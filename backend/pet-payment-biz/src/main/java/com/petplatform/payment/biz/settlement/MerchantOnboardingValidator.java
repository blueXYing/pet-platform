package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileInput;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation of the operator-entered onboarding profile (contract 81 §4.4), with the length and
 * alphabet limits of Lakala 1020/1208 and the mainland resident ID rule of SSOT §二十七.
 * Inputs are trimmed; a blank sensitive field may be replaced by the stored value before this runs.
 */
final class MerchantOnboardingValidator {
    static final Set<String> REQUIRED_ATTACHMENTS = Set.of(
            "FR_ID_CARD_FRONT", "FR_ID_CARD_BEHIND", "BANK_CARD", "BUSINESS_LICENCE",
            "MERCHANT_PHOTO", "SHOPINNER");
    static final Set<String> SETTLER_ATTACHMENTS = Set.of(
            "ID_CARD_FRONT", "ID_CARD_BEHIND", "NO_LEGAL_PERSON_SETT_AUTH_LETTER");
    static final Set<String> KNOWN_ATTACHMENTS = Set.of(
            "FR_ID_CARD_FRONT", "FR_ID_CARD_BEHIND", "BANK_CARD", "BUSINESS_LICENCE",
            "MERCHANT_PHOTO", "SHOPINNER", "ID_CARD_FRONT", "ID_CARD_BEHIND",
            "NO_LEGAL_PERSON_SETT_AUTH_LETTER");
    static final String RESIDENT_ID_TYPE = "01";
    private static final Pattern DIGITS_6_8 = Pattern.compile("[0-9]{6,8}");
    private static final Pattern MCC = Pattern.compile("[0-9]{4}");
    private static final Pattern LICENSE_NO = Pattern.compile("[0-9A-Z]{8,40}");
    private static final Pattern HAN_NAME = Pattern.compile("[\\p{IsHan}·]{2,20}");
    private static final Pattern ID_15 = Pattern.compile("[0-9]{15}");
    private static final Pattern ID_18 = Pattern.compile("[0-9]{17}[0-9X]");
    private static final Pattern MOBILE = Pattern.compile("1[0-9]{10}");
    private static final Pattern CARD_NO = Pattern.compile("[0-9]{8,40}");
    private static final Pattern BANK_CODE = Pattern.compile("[0-9A-Za-z]{6,20}");
    private static final Pattern ASSET_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CHECKS = "10X98765432".toCharArray();

    /** Normalized profile: every optional is either a trimmed non-blank value or {@code null}. */
    record Profile(MerchantProfileInput merchant, SettlementProfileInput settlement,
            Map<String, String> attachments, String cooperationAssetId, String reason) {}

    private MerchantOnboardingValidator() {}

    static Profile validate(SaveOnboardingCommand command, LocalDate today) {
        if (command == null) throw invalid("请求体不能为空");
        MerchantProfileInput raw = command.merchant();
        SettlementProfileInput settle = command.settlement();
        if (raw == null || settle == null) throw invalid("主体信息与结算账户不能为空");

        String merRegName = text(raw.merRegName(), 4, 80, "商户注册名称");
        String merBlisName = text(raw.merBlisName(), 4, 80, "营业执照名称");
        if (!merRegName.equals(merBlisName)) throw invalid("商户注册名称须与营业执照名称一致");
        String merBizName = optional(raw.merBizName(), 4, 64, "商户经营名称");
        String merRegDistCode = matches(raw.merRegDistCode(), DIGITS_6_8, "拉卡拉地区码须为 6～8 位数字");
        String merRegAddr = text(raw.merRegAddr(), 6, 80, "商户详细地址");
        String mccCode = raw.mccCode() == null || raw.mccCode().isBlank()
                ? null : matches(raw.mccCode(), MCC, "MCC 须为 4 位数字");
        // Operators may type the unified social credit code in lower case; Lakala expects upper case.
        String merBlisNo = raw.merBlisNo() == null ? "" : raw.merBlisNo().trim().toUpperCase(java.util.Locale.ROOT);
        merBlisNo = matches(merBlisNo, LICENSE_NO, "营业执照号须为 8～40 位数字与大写字母");
        LocalDate merBlisStDt = date(raw.merBlisStDt(), "营业执照开始日期");
        LocalDate merBlisExpDt = date(raw.merBlisExpDt(), "营业执照有效期");
        ordered(merBlisStDt, merBlisExpDt, "营业执照", today);

        String larName = matches(raw.larName(), HAN_NAME, "法人姓名须为 2～20 个汉字");
        String larIdcard = residentId(raw.larIdcard(), "法人身份证");
        LocalDate larIdcardStDt = date(raw.larIdcardStDt(), "法人身份证开始日期");
        LocalDate larIdcardExpDt = date(raw.larIdcardExpDt(), "法人身份证有效期");
        ordered(larIdcardStDt, larIdcardExpDt, "法人身份证", today);
        String larMobile = matches(raw.larMobile(), MOBILE, "法人手机号格式不正确");
        String contactName = matches(raw.contactName(), HAN_NAME, "联系人须为 2～20 个汉字");
        String contactMobile = matches(raw.contactMobile(), MOBILE, "联系人手机号格式不正确");

        String acctTypeCode = settle.acctTypeCode() == null ? "" : settle.acctTypeCode().trim();
        if (!"57".equals(acctTypeCode) && !"58".equals(acctTypeCode)) throw invalid("结算账户性质须为 57 对公或 58 对私");
        String acctName = text(settle.acctName(), 2, 40, "结算账户名称");
        if ("57".equals(acctTypeCode) && !acctName.equals(merRegName)) throw invalid("对公结算账户名称须与商户注册名称一致");
        String acctNo = matches(settle.acctNo(), CARD_NO, "结算账户卡号须为 8～40 位数字");
        String acctIdcard = blank(settle.acctIdcard()) ? null : settle.acctIdcard().trim();
        String acctIdExpDtText = blank(settle.acctIdExpDt()) ? null : settle.acctIdExpDt().trim();
        boolean settlerGiven = !blank(settle.acctIdType());
        if (settlerGiven ? (acctIdcard == null || acctIdExpDtText == null)
                : (acctIdcard != null || acctIdExpDtText != null)) {
            throw invalid("结算人证件类型、证件号与有效期须同时填写或同时留空");
        }
        if (settlerGiven && !RESIDENT_ID_TYPE.equals(settle.acctIdType().trim())) {
            throw invalid("结算人证件类型仅支持身份证");
        }
        LocalDate acctIdExpDt = acctIdExpDtText == null ? null : date(acctIdExpDtText, "结算人证件有效期");
        if (acctIdcard != null) {
            acctIdcard = residentId(acctIdcard, "结算人身份证");
            if (acctIdcard.equals(larIdcard)) throw invalid("结算人证件号与法人相同，请留空结算人信息");
            if (acctIdExpDt.isBefore(today)) throw invalid("结算人身份证已过期");
        }
        String openningBankCode = matches(settle.openningBankCode(), BANK_CODE, "开户行号须为 6～20 位数字或字母");
        String openningBankName = text(settle.openningBankName(), 2, 40, "开户行名称");
        String clearingBankCode = matches(settle.clearingBankCode(), BANK_CODE, "清算行号须为 6～20 位数字或字母");

        Map<String, String> attachments = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : command.attachments().entrySet()) {
            if (!KNOWN_ATTACHMENTS.contains(entry.getKey())) throw invalid("不支持的附件类型：" + entry.getKey());
            if (!blank(entry.getValue())) {
                attachments.put(entry.getKey(), matches(entry.getValue(), ASSET_ID, "附件素材编号格式不正确"));
            }
        }
        for (String required : REQUIRED_ATTACHMENTS) {
            if (!attachments.containsKey(required)) throw invalid("缺少必传附件：" + required);
        }
        if (acctIdcard != null) {
            if (!"58".equals(acctTypeCode)) throw invalid("仅对私结算支持非法人结算人");
            for (String required : SETTLER_ATTACHMENTS) {
                if (!attachments.containsKey(required)) throw invalid("非法人结算缺少附件：" + required);
            }
        } else {
            // Contract 81 §4.4: the settler attachments must be absent when the settler is the legal person.
            for (String extra : SETTLER_ATTACHMENTS) {
                if (attachments.containsKey(extra)) throw invalid("结算人为法人本人时以下附件必须留空：" + extra);
            }
        }
        String cooperationAssetId = blank(command.cooperationAssetId()) ? null
                : matches(command.cooperationAssetId(), ASSET_ID, "合作协议素材编号格式不正确");
        String reason = text(command.reason(), 2, 256, "操作原因");

        return new Profile(
                new MerchantProfileInput(merRegName, merBizName, merRegDistCode, merRegAddr, mccCode,
                        merBlisName, merBlisNo, iso(merBlisStDt), iso(merBlisExpDt), larName, larIdcard,
                        iso(larIdcardStDt), iso(larIdcardExpDt), larMobile, contactName, contactMobile),
                new SettlementProfileInput(acctTypeCode, acctName, acctNo,
                        acctIdcard == null ? null : RESIDENT_ID_TYPE, acctIdcard,
                        acctIdExpDt == null ? null : iso(acctIdExpDt),
                        openningBankCode, openningBankName, clearingBankCode),
                Map.copyOf(attachments), cooperationAssetId, reason);
    }

    /** GB 11643: 18 digits with check digit (lower-case x accepted), or the legacy 15-digit form. */
    static String residentId(String raw, String label) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (ID_15.matcher(value).matches()) return value;
        if (!ID_18.matcher(value).matches()) throw invalid(label + "格式不正确");
        // Birth date must be a real calendar day between 1900 and today (checked before the check digit).
        LocalDate birth;
        try {
            birth = LocalDate.of(Integer.parseInt(value.substring(6, 10)),
                    Integer.parseInt(value.substring(10, 12)), Integer.parseInt(value.substring(12, 14)));
        } catch (java.time.DateTimeException impossibleDate) {
            throw invalid(label + "出生日期不正确");
        }
        if (birth.getYear() < 1900 || birth.isAfter(LocalDate.now())) throw invalid(label + "出生日期不正确");
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (value.charAt(i) - '0') * ID_WEIGHTS[i];
        if (ID_CHECKS[sum % 11] != value.charAt(17)) throw invalid(label + "校验位不正确");
        return value;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(String raw, int min, int max, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.codePointCount(0, value.length()) < min || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(label + "须为 " + min + "～" + max + " 个字符");
        }
        return value;
    }

    private static String optional(String raw, int min, int max, String label) {
        return blank(raw) ? null : text(raw, min, max, label);
    }

    private static String matches(String raw, Pattern pattern, String message) {
        String value = raw == null ? "" : raw.trim();
        if (!pattern.matcher(value).matches()) throw invalid(message);
        return value;
    }

    private static LocalDate date(String raw, String label) {
        if (blank(raw)) throw invalid(label + "不能为空");
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException invalidDate) {
            throw invalid(label + "格式须为 yyyy-MM-dd");
        }
    }

    private static void ordered(LocalDate from, LocalDate to, String label, LocalDate today) {
        if (from.isAfter(to)) throw invalid(label + "起止日期顺序不正确");
        if (to.isBefore(today)) throw invalid(label + "已过期");
    }

    private static String iso(LocalDate date) {
        return date.toString();
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }
}
