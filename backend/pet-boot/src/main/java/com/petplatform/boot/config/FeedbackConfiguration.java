package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import com.petplatform.customerservice.biz.infrastructure.persistence.FeedbackTicketStore;
import com.petplatform.user.api.query.UserActiveStatusApi;
import com.petplatform.user.biz.apiimpl.UserActiveStatusApiImpl;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 67 feedback-ticket slice (PRD 5.1.28 complaint class; CCR-CS-FEEDBACK-001):
 * assembled only when the C session slice AND this slice's switch are both on. The kernel
 * consumes the user-owned active-status fact port, so a missing port bean is an assembly
 * error, never a runtime fallback. Default off in production until the deployment gate opens
 * it (pet.feedback.http.enabled).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.c.enabled", "feedback.http.enabled"}, havingValue = "true")
public class FeedbackConfiguration {

    @Bean
    UserActiveStatusApi userActiveStatus(DataSource source) {
        return new UserActiveStatusApiImpl(source);
    }

    @Bean
    FeedbackTicketService feedbackTicketService(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            UserActiveStatusApi activeUsers) {
        return new FeedbackTicketService(new FeedbackTicketStore(source), activeUsers,
                idProvider(ids));
    }

    private static SnowflakeIdGenerator idProvider(ObjectProvider<SnowflakeIdGenerator> ids) {
        return java.util.Objects.requireNonNull(ids.getIfAvailable(),
                "PLAT-002 ID provider required before feedback writes can start");
    }
}
