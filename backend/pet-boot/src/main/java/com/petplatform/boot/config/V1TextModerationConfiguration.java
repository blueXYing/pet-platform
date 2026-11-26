package com.petplatform.boot.config;

import com.petplatform.aftersale.biz.application.AfterSalePorts;
import com.petplatform.order.biz.application.MerchantOrderPorts;
import com.petplatform.refund.biz.application.RefundApplicationPorts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V1 baseline local text-moderation adapter for order/refund/aftersale reason fields (matching
 * {@link BookingCreationConfiguration#orderCreationRemarkPolicy()}): computes the exact UTF-8
 * SHA-256 evidence digest and records policy version {@code V1_LOCAL_BASELINE}. Any deployment
 * with an external content-moderation provider may supply its own bean to supersede these
 * {@link ConditionalOnMissingBean} defaults.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.payment", name = "enabled", havingValue = "true")
public class V1TextModerationConfiguration {
    private static final String POLICY_VERSION = "V1_LOCAL_BASELINE";

    @Bean
    @ConditionalOnMissingBean(MerchantOrderPorts.Moderation.class)
    MerchantOrderPorts.Moderation v1MerchantOrderModeration() {
        return text -> new MerchantOrderPorts.Approval(sha256(text), POLICY_VERSION, true);
    }

    @Bean
    @ConditionalOnMissingBean(RefundApplicationPorts.Moderation.class)
    RefundApplicationPorts.Moderation v1RefundApplicationModeration() {
        return text -> new RefundApplicationPorts.Approval(sha256(text), POLICY_VERSION, true);
    }

    @Bean
    @ConditionalOnMissingBean(AfterSalePorts.Moderation.class)
    AfterSalePorts.Moderation v1AfterSaleModeration() {
        return text -> new AfterSalePorts.Approval(sha256(text), POLICY_VERSION, true);
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
