package com.shivang.obd.security.config;

import com.shivang.obd.authz.context.MembershipResolver;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrganizationContextFilterRegistration {

    @Bean
    public FilterRegistrationBean<OrganizationContextPopulationFilter> organizationContextPopulationFilterRegistration(
        List<MembershipResolver> membershipResolvers
    ) {
        var filter = new OrganizationContextPopulationFilter(membershipResolvers);
        FilterRegistrationBean<OrganizationContextPopulationFilter> registration =
            new FilterRegistrationBean<>(filter);
        registration.setOrder(0);
        return registration;
    }
}
