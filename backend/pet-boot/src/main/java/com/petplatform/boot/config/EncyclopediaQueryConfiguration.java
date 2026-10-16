package com.petplatform.boot.config;

import com.petplatform.community.biz.application.EncyclopediaContentService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Encyclopedia public read face (contract 62, PRD 5.1.4): default ON per the 2026-10-16
 * deployment ruling (doc 35) — platform-owned read logic with no external credentials. The
 * HTTP face additionally rides the C session domain's route chain at deployment. Read-only —
 * no write path, no idempotency, no outbox dependency exists in this assembly.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.encyclopedia.query", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EncyclopediaQueryConfiguration {

    @Bean
    EncyclopediaContentService encyclopediaContentService(DataSource source) {
        return new EncyclopediaContentService(source);
    }
}
