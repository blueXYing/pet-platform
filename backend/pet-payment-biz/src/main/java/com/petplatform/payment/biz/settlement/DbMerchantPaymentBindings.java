package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentOnboardingStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentOnboardingMapper.OnboardingRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ConfigRow;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Mode A (Ruling 40 §10 / CCR-PAY-CENTER-001 §14) per-merchant Lakala acquiring bindings.
 *
 * <p>Resolves {@link #require(String, String)} strictly from the merchant's own enabled
 * {@code settlement_config(lakala_merchant_no, lakala_term_no)} row plus the platform miniapp
 * {@code subAppId}. Unconfigured or disabled merchants fail closed without ever falling back to
 * the platform主体 merchant number. {@link #requirePlatform()} continues returning the platform
 *主体 binding for platform-level identity and non-order DEMO use.
 *
 * <p>Ruling 44 §2: a merchant with an acquiring onboarding record that has not reached {@code ACTIVE}
 * fails closed even if a pair was typed earlier — Lakala settles nothing for split-flagged trades of a
 * merchant whose split business is not opened and bound. Before the onboarding migration (V44) has been
 * applied no onboarding record can exist, so a missing ledger table counts as "no record" and never
 * blocks payments of merchants that were configured by hand.
 */
public final class DbMerchantPaymentBindings implements PaymentMerchantBindings {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final PaymentSettlementStore store;
    private final PaymentOnboardingStore onboarding;
    private final Binding platformBinding;

    public DbMerchantPaymentBindings(DataSource source, Binding platformBinding) {
        Objects.requireNonNull(source, "source");
        this.platformBinding = Objects.requireNonNull(platformBinding, "platformBinding");
        this.store = new PaymentSettlementStore(source);
        this.onboarding = new PaymentOnboardingStore(source);
    }

    @Override
    public Binding require(String merchantId, String storeId) {
        if (merchantId == null || merchantId.isBlank() || storeId == null || storeId.isBlank()) {
            throw unavailable("merchant or store identity is missing");
        }
        final long parsedMerchantId;
        try {
            parsedMerchantId = IDS.fromApi(merchantId);
        } catch (IllegalArgumentException invalid) {
            throw unavailable("merchant identity is invalid");
        }
        ConfigRow config;
        try {
            config = store.settlement.selectConfigByMerchant(parsedMerchantId);
        } catch (RuntimeException dbFailure) {
            throw unavailable("merchant acquiring configuration is unavailable");
        }
        if (config == null || !config.enabled
                || blank(config.lakalaMerchantNo) || blank(config.lakalaTermNo)) {
            throw unavailable("商家拉卡拉收单商户号或终端号未配置");
        }
        OnboardingRow onboarding;
        try {
            onboarding = this.onboarding.findIfApplied(parsedMerchantId);
        } catch (RuntimeException dbFailure) {
            throw unavailable("商家收单开通状态不可用");
        }
        if (onboarding != null && !"ACTIVE".equals(onboarding.status)) {
            throw unavailable("商家收单开通尚未完成，暂不能支付");
        }
        return new Binding(config.lakalaMerchantNo.trim(), config.lakalaTermNo.trim(),
                platformBinding.subAppId());
    }

    @Override
    public Binding requirePlatform() {
        return platformBinding;
    }

    @Override
    public Optional<String> findAcquiringMerchantNo(long merchantId) {
        if (merchantId <= 0) {
            return Optional.empty();
        }
        try {
            ConfigRow config = store.settlement.selectConfigByMerchant(merchantId);
            if (config == null || !config.enabled || blank(config.lakalaMerchantNo)) {
                return Optional.empty();
            }
            return Optional.of(config.lakalaMerchantNo.trim());
        } catch (RuntimeException dbFailure) {
            return Optional.empty();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
