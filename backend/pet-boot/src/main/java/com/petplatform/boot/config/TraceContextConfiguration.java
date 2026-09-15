package com.petplatform.boot.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers the trace filter ahead of every other chain, including Spring Security. */
@Configuration(proxyBeanMethods = false)
public class TraceContextConfiguration {

    @Bean
    FilterRegistrationBean<TraceContextFilter> traceContextFilter() {
        var registration = new FilterRegistrationBean<>(new TraceContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceContextFilter");
        return registration;
    }
}
