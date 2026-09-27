package com.shivang.obd.common.audit;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditConfig {

    @Bean
    public AuditorProvider auditorProvider(ObjectProvider<AuditorIdentitySource> sources) {
        return new AuditorProvider(sources.stream().toList());
    }
}
