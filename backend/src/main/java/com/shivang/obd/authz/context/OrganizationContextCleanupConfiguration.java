package com.shivang.obd.authz.context;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrganizationContextCleanupConfiguration {

    @Bean
    public FilterRegistrationBean<OrganizationContextFilter> organizationContextCleanupFilterRegistration() {
        FilterRegistrationBean<OrganizationContextFilter> registration =
            new FilterRegistrationBean<>(new OrganizationContextFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        return registration;
    }
}
