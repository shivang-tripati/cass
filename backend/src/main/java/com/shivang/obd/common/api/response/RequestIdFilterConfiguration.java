package com.shivang.obd.common.api.response;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestIdFilterConfiguration {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration =
            new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
