package com.petplatform.boot.config;

import com.petplatform.order.biz.apiimpl.OrderQueryApiImpl;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end order list/detail read slice (HTTP contract 10 §3.7, C-004): assembled with the C-end
 * session switch, read-only, no writers exist in this slice.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class OrderQueryReadConfiguration {

    @Bean
    OrderQueryApiImpl orderQueryApi(DataSource source) {
        return new OrderQueryApiImpl(source);
    }
}
