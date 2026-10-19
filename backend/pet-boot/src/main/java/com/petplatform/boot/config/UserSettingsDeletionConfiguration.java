package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderDeletionFactsApiImpl;
import com.petplatform.aftersale.biz.apiimpl.AfterSaleDeletionFactsApiImpl;
import com.petplatform.user.biz.application.AccountDeletionService;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 66 user settings / account-deletion slice (PRD 5.1.29; CCR-USR-DELETION-001):
 * assembled only when the C session slice, this slice's switch AND the merchant admission
 * slice (OWNER membership surface) are all on. The deletion kernel consumes the order/aftersale
 * leaf fact ports and the frozen OWNER-membership surface, so a missing port bean is an
 * assembly error, never a runtime fallback. Default off in production until the deployment
 * gate opens it (pet.usersettings.http.enabled).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.c.enabled", "usersettings.http.enabled", "merchant.application.enabled"},
        havingValue = "true")
public class UserSettingsDeletionConfiguration {

    @Bean
    OrderDeletionFactsApi orderDeletionFacts(DataSource source) {
        return new OrderDeletionFactsApiImpl(source);
    }

    @Bean
    AfterSaleDeletionFactsApi afterSaleDeletionFacts(DataSource source) {
        return new AfterSaleDeletionFactsApiImpl(source);
    }

    @Bean
    AccountDeletionService accountDeletionService(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            OrderDeletionFactsApi orderFacts,
            AfterSaleDeletionFactsApi aftersaleFacts,
            ObjectProvider<MerchantAdmissionQueryApi> memberships) {
        return new AccountDeletionService(source, idProvider(ids), orderFacts, aftersaleFacts,
                java.util.Objects.requireNonNull(memberships.getIfAvailable(),
                        "OWNER membership surface required before deletion precheck can start"));
    }

    private static SnowflakeIdGenerator idProvider(ObjectProvider<SnowflakeIdGenerator> ids) {
        return java.util.Objects.requireNonNull(ids.getIfAvailable(),
                "PLAT-002 ID provider required before deletion writes can start");
    }
}
