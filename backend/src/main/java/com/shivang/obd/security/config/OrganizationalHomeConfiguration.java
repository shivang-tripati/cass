package com.shivang.obd.security.config;

import com.shivang.obd.authz.RoleAssignmentPolicy;
import com.shivang.obd.authz.context.MembershipResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrganizationalHomeConfiguration {

    @Bean
    public RoleAssignmentPolicy roleAssignmentPolicy(
        com.shivang.obd.authz.RoleRepository roleRepository,
        List<MembershipResolver> membershipResolvers
    ) {
        RoleAssignmentPolicy.OrganizationalHomeChecker checker = new RoleAssignmentPolicy.OrganizationalHomeChecker() {
            @Override
            public boolean hasActiveTenantMembership(UUID userId) {
                return membershipResolvers.stream()
                    .anyMatch(r -> r.hasActiveTenantMembership(userId));
            }

            @Override
            public boolean hasActiveResellerMembership(UUID userId) {
                return membershipResolvers.stream()
                    .anyMatch(r -> r.hasActiveResellerMembership(userId));
            }
        };
        return new RoleAssignmentPolicy(roleRepository, checker);
    }
}
